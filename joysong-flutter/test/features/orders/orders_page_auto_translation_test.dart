import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_controller.dart';
import 'package:joysong_flutter/features/orders/presentation/orders_page.dart';

import '../../core/translation/translation_test_fixtures.dart';
import 'order_test_fixtures.dart';

void main() {
  testWidgets('opted-in order cards translate only project and institution',
      (tester) async {
    await _useTallSurface(tester);
    final ordersRepository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          id: 'order-a',
          projectName: '项目甲',
          institutionName: '机构甲',
          doctorName: '张医生',
          remark: '不要翻译备注',
        ),
      ];
    final translations = RecordingTranslationRepository()..holdResponses = true;
    final controller = OrdersController(ordersRepository);
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _ordersHost(
        ordersRepository: ordersRepository,
        translations: translations,
        enableAutoTranslation: true,
        controller: controller,
        autoController: autoController,
      ),
    );
    await tester.pump();

    expect(_mountedRequests(tester), const {
      ('project', 'order:order-a', 'projectName', '项目甲'),
      ('institution', 'order:order-a', 'institutionName', '机构甲'),
    });
    expect(translations.calls.map((call) => call.text), isNot(contains('张医生')));
    expect(
      translations.calls.map((call) => call.text),
      isNot(contains('不要翻译备注')),
    );

    translations.completeText('项目甲', 'Project A');
    translations.completeText('机构甲', 'Institution A');
    await tester.pump();
    await tester.pump();

    expect(find.text('Project A'), findsOneWidget);
    expect(find.text('Institution A'), findsOneWidget);
    expect(find.text('Dr. 张医生'), findsOneWidget);
  });

  testWidgets(
      'order cards retain translation ownership through prepend reorder and refresh',
      (tester) async {
    await _useTallSurface(tester);
    final orderA = sampleOrder(
      id: 'order-a',
      projectName: '项目甲',
      institutionName: '机构甲',
    );
    final orderB = sampleOrder(
      id: 'order-b',
      projectName: '项目乙',
      institutionName: '机构乙',
    );
    final orderC = sampleOrder(
      id: 'order-c',
      projectName: '项目丙',
      institutionName: '机构丙',
    );
    final ordersRepository = FakeOrdersRepository()..orders = [orderA, orderB];
    final translations = RecordingTranslationRepository()..holdResponses = true;
    final controller = OrdersController(ordersRepository);
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _ordersHost(
        ordersRepository: ordersRepository,
        translations: translations,
        enableAutoTranslation: true,
        controller: controller,
        autoController: autoController,
      ),
    );
    await tester.pump();
    expect(translations.calls, hasLength(4));

    await _completeTranslations(tester, translations, const {
      '项目甲': 'Project A',
      '机构甲': 'Institution A',
      '项目乙': 'Project B',
      '机构乙': 'Institution B',
    });
    expect(_cardText('order-a', 'Project A'), findsOneWidget);
    expect(_cardText('order-b', 'Project B'), findsOneWidget);

    ordersRepository.orders = [orderB, orderA];
    await controller.refresh();
    await tester.pump();
    await tester.pump();

    expect(translations.calls, hasLength(4));
    expect(_cardText('order-a', 'Project A'), findsOneWidget);
    expect(_cardText('order-b', 'Project B'), findsOneWidget);
    expect(_cardText('order-a', 'Institution A'), findsOneWidget);
    expect(_cardText('order-b', 'Institution B'), findsOneWidget);

    ordersRepository.orders = [orderC, orderB, orderA];
    await controller.refresh();
    await tester.pump();
    expect(translations.calls, hasLength(6));
    await _completeTranslations(tester, translations, const {
      '项目丙': 'Project C',
      '机构丙': 'Institution C',
    });

    expect(_cardText('order-a', 'Project A'), findsOneWidget);
    expect(_cardText('order-b', 'Project B'), findsOneWidget);
    expect(_cardText('order-c', 'Project C'), findsOneWidget);
    expect(_cardText('order-a', 'Institution A'), findsOneWidget);
    expect(_cardText('order-b', 'Institution B'), findsOneWidget);
    expect(_cardText('order-c', 'Institution C'), findsOneWidget);
  });

  testWidgets(
      'failed order card retries only after an identical accepted refresh snapshot',
      (tester) async {
    await _useTallSurface(tester);
    final ordersRepository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          id: 'retry-order',
          projectName: '刷新重试项目',
          institutionName: '',
        ),
      ];
    final translations = RecordingTranslationRepository()
      ..failuresRemaining = 1;
    final controller = OrdersController(ordersRepository);
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _ordersHost(
        ordersRepository: ordersRepository,
        translations: translations,
        enableAutoTranslation: true,
        controller: controller,
        autoController: autoController,
      ),
    );
    await tester.pumpAndSettle();

    expect(translations.calls.map((call) => call.text), ['刷新重试项目']);
    expect(find.text('刷新重试项目'), findsOneWidget);

    await tester.tap(find.text('All'));
    await tester.pump();
    await tester.pumpWidget(
      _ordersHost(
        ordersRepository: ordersRepository,
        translations: translations,
        enableAutoTranslation: true,
        controller: controller,
        autoController: autoController,
      ),
    );
    await tester.pump();
    expect(translations.calls, hasLength(1));

    ordersRepository.orders = [
      sampleOrder(
        id: 'retry-order',
        projectName: '刷新重试项目',
        institutionName: '',
      ),
    ];
    await tester
        .widget<RefreshIndicator>(find.byType(RefreshIndicator))
        .onRefresh();
    await tester.pumpAndSettle();

    expect(translations.calls.map((call) => call.text), [
      '刷新重试项目',
      '刷新重试项目',
    ]);
    expect(find.text('en-US:刷新重试项目'), findsOneWidget);
  });

  testWidgets('empty and duplicate order IDs remain source-only',
      (tester) async {
    await _useTallSurface(tester);
    final ordersRepository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(
          id: ' ',
          projectName: '空身份项目',
          institutionName: '空身份机构',
        ),
        sampleOrder(
          id: 'duplicate-order',
          projectName: '重复项目甲',
          institutionName: '重复机构甲',
        ),
        sampleOrder(
          id: 'duplicate-order',
          projectName: '重复项目乙',
          institutionName: '重复机构乙',
        ),
        sampleOrder(
          id: 'fallback-order',
          projectName: '回退项目',
          institutionName: '',
        ),
      ];
    final translations = RecordingTranslationRepository();
    final controller = OrdersController(ordersRepository);
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _ordersHost(
        ordersRepository: ordersRepository,
        translations: translations,
        enableAutoTranslation: true,
        controller: controller,
        autoController: autoController,
      ),
    );
    await tester.pumpAndSettle();

    for (final text in const [
      '空身份项目',
      '空身份机构',
      '重复项目甲',
      '重复机构甲',
      '重复项目乙',
      '重复机构乙',
      'Joysong booking',
    ]) {
      expect(find.text(text), findsOneWidget, reason: text);
    }
    expect(translations.calls.map((call) => call.text), ['回退项目']);
    expect(
      _mountedRequests(tester),
      const {('project', 'order:fallback-order', 'projectName', '回退项目')},
    );
  });

  testWidgets('orders page defaults off under an active scope', (tester) async {
    await _useTallSurface(tester);
    final ordersRepository = FakeOrdersRepository()
      ..orders = [
        sampleOrder(projectName: '默认关闭项目', institutionName: '默认关闭机构'),
      ];
    final translations = RecordingTranslationRepository();
    final controller = OrdersController(ordersRepository);
    addTearDown(controller.dispose);
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);

    await tester.pumpWidget(
      _ordersHost(
        ordersRepository: ordersRepository,
        translations: translations,
        controller: controller,
        autoController: autoController,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('默认关闭项目'), findsOneWidget);
    expect(find.text('默认关闭机构'), findsOneWidget);
    expect(translations.calls, isEmpty);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
  });
}

Set<(String, String, String, String)> _mountedRequests(WidgetTester tester) =>
    tester
        .widgetList<AutoTranslationBuilder>(find.byType(AutoTranslationBuilder))
        .map((widget) => (
              widget.request.contentType,
              widget.request.contentId,
              widget.request.field,
              widget.request.sourceText,
            ))
        .toSet();

Finder _cardText(String orderId, String text) => find.descendant(
      of: find.byKey(ValueKey<String>('consumer-order:$orderId')),
      matching: find.text(text),
    );

Future<void> _useTallSurface(WidgetTester tester) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = const Size(1200, 1800);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
}

Future<void> _completeTranslations(
  WidgetTester tester,
  RecordingTranslationRepository translations,
  Map<String, String> values,
) async {
  for (final entry in values.entries) {
    translations.completeText(entry.key, entry.value);
  }
  await tester.pump();
  await tester.pump();
}

AutoTranslationController _activeAutoController(
  TranslationRepository repository,
) {
  final controller = AutoTranslationController(
    repository: repository,
    maxConcurrent: 10,
  );
  controller.synchronize(
    enabled: true,
    authenticated: true,
    targetLanguage: 'en-US',
  );
  return controller;
}

Widget _ordersHost({
  required FakeOrdersRepository ordersRepository,
  required RecordingTranslationRepository translations,
  required OrdersController controller,
  required AutoTranslationController autoController,
  bool enableAutoTranslation = false,
}) =>
    AutoTranslationScope(
      controller: autoController,
      enabled: true,
      targetLanguage: 'en-US',
      child: MaterialApp(
        locale: const Locale('en'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: OrdersPage(
          controller: controller,
          onOrderSelected: (_) {},
          enableAutoTranslation: enableAutoTranslation,
        ),
      ),
    );
