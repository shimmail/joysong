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

  testWidgets(
      'status card trims operand names and ignores unknown missing fields',
      (tester) async {
    await _pumpCard(
      tester,
      const AgentComparisonStatusCard(request: _whitespaceRequest),
      locale: const Locale('en'),
    );

    expect(find.text('Clinic A · Clinic B'), findsOneWidget);
    expect(find.textContaining('FUTURE_FIELD'), findsNothing);
    expect(find.text('Select at least two items to compare'), findsNothing);
    expect(
      find.text(
        'Specify whether to compare clinics, doctors, treatments, or clinic treatments',
      ),
      findsNothing,
    );
  });

  testWidgets('single item comparison report still uses comparison table',
      (tester) async {
    await _pumpCard(
      tester,
      AgentCatalogReportCard(report: _projectReport),
      locale: const Locale('en'),
    );

    expect(find.byType(AgentComparisonTable), findsOneWidget);
    expect(find.byType(AgentCatalogDetailCard), findsNothing);
    expect(find.text('Comparison'), findsOneWidget);
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

    for (final entry in const {
      'Category': 'Skin',
      'Reference price': r'$100',
      'Rating': '4.7',
      'Tags': 'Popular',
    }.entries) {
      expect(find.text(entry.key), findsOneWidget);
      expect(find.text(entry.value), findsOneWidget);
    }
  });

  testWidgets(
      'institution project table shows city category prices rating reviews sales verification and tags',
      (tester) async {
    await _pumpCard(
      tester,
      AgentComparisonTable(report: _institutionProjectReport),
    );

    for (final entry in const {
      'City': 'Shanghai',
      'Category': 'Skin',
      'Clinic price': r'$90',
      'Reference price': r'$100',
      'Rating': '4.7',
      'Review count': '20',
      'Sales': '30',
      'Clinic verified': 'Verified',
      'Tags': 'Popular',
    }.entries) {
      expect(find.text(entry.key), findsOneWidget);
      expect(find.text(entry.value), findsOneWidget);
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

  testWidgets(
      'doctor practice institutions cell lays out the complete value without ellipsis',
      (tester) async {
    await _pumpCard(
      tester,
      AgentComparisonTable(report: _longDoctorReport),
      locale: const Locale('en'),
      textScaler: const TextScaler.linear(2),
      width: 300,
    );

    final valueFinder = find.text(_longPracticeInstitutions);
    final value = tester.widget<Text>(valueFinder);
    expect(value.maxLines, isNull);
    expect(value.overflow, isNull);
    expect(tester.getSize(valueFinder).height, greaterThan(88));
    expect(tester.takeException(), isNull);
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

  testWidgets(
      'comparison table falls back to the union of structured attribute keys',
      (tester) async {
    await _pumpCard(tester, AgentComparisonTable(report: _fallbackReport));

    expect(find.text('Backend key A'), findsOneWidget);
    expect(find.text('Alpha value'), findsOneWidget);
    expect(find.text('Backend key B'), findsOneWidget);
    expect(find.text('Beta value'), findsOneWidget);
  });

  testWidgets('comparison report honors onOpen and canOpen', (tester) async {
    AgentCatalogItem? opened;
    await _pumpCard(
      tester,
      AgentCatalogReportCard(
        report: _actionReport,
        onOpen: (item) => opened = item,
        canOpen: (item) => item.id == 'project-open',
      ),
      locale: const Locale('en'),
    );

    await tester.tap(find.text('Open project'));
    expect(opened?.id, 'project-open');

    await tester.tap(find.text('Blocked project'), warnIfMissed: false);
    expect(opened?.id, 'project-open');
    expect(find.text('Information is incomplete'), findsOneWidget);
  });
}

Future<void> _pumpCard(
  WidgetTester tester,
  Widget child, {
  Locale locale = const Locale('zh'),
  TextScaler textScaler = TextScaler.noScaling,
  double? width,
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
        home: MediaQuery(
          data: MediaQueryData(textScaler: textScaler),
          child: Scaffold(
            body: SizedBox(width: width, child: child),
          ),
        ),
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

const _whitespaceRequest = AgentComparisonRequest(
  operands: [
    AgentComparisonOperand(
      entityType: 'INSTITUTION',
      entityId: 'clinic-a',
      displayName: '  Clinic A  ',
    ),
    AgentComparisonOperand(
      entityType: 'INSTITUTION',
      entityId: 'blank',
      displayName: '   ',
    ),
    AgentComparisonOperand(
      entityType: 'INSTITUTION',
      entityId: 'clinic-b',
      displayName: ' Clinic B ',
    ),
  ],
  targetType: 'INSTITUTION',
  dimensions: [],
  constraints: {},
  missingFields: {'FUTURE_FIELD'},
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

const _longPracticeInstitutions =
    'Clinic A (Shanghai), Clinic B (Beijing), Clinic C (Shenzhen); 2 more';

final _longDoctorReport = _report(
  items: [
    _item(
      type: 'DOCTOR',
      id: 'doctor-long',
      name: 'Dr',
      attributes: const {
        'Practice institutions': _longPracticeInstitutions,
      },
    ),
  ],
  dimensions: const ['Practice institutions'],
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

final _fallbackReport = _report(
  items: [
    _item(
      type: 'PROJECT',
      id: 'project-a',
      name: 'Project A',
      attributes: const {'Backend key A': 'Alpha value'},
    ),
    _item(
      type: 'PROJECT',
      id: 'project-b',
      name: 'Project B',
      attributes: const {'Backend key B': 'Beta value'},
    ),
  ],
  dimensions: const [],
);

final _actionReport = _report(
  items: [
    _item(
      type: 'PROJECT',
      id: 'project-open',
      name: 'Open project',
      attributes: const {'Rating': '4.8'},
    ),
    _item(
      type: 'PROJECT',
      id: 'project-blocked',
      name: 'Blocked project',
      attributes: const {'Rating': '4.7'},
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
