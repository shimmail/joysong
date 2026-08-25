import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/orders/presentation/order_detail_page.dart';
import 'package:joysong_flutter/features/orders/presentation/review_order_controller.dart';
import 'package:joysong_flutter/features/social/domain/social_models.dart';

import '../../core/translation/translation_test_fixtures.dart';
import 'order_test_fixtures.dart';

void main() {
  testWidgets('previews and removes uploaded images with a visible counter', (
    tester,
  ) async {
    _useTallScreen(tester);
    final controller = ReviewOrderController();
    addTearDown(controller.dispose);
    var image = 0;
    await tester.pumpWidget(
      _testApp(
        ReviewOrderPage(
          order: sampleOrder(),
          controller: controller,
          onPickImage: () async => 'https://img/${++image}.jpg',
        ),
      ),
    );

    expect(find.text('0/6'), findsOneWidget);
    await tester.tap(find.byKey(const Key('review-image-add-button')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('review-image-preview-0')), findsOneWidget);
    expect(find.text('1/6'), findsOneWidget);
    await tester.tap(find.byKey(const Key('review-image-remove-0')));
    await tester.pump();
    expect(find.byKey(const Key('review-image-preview-0')), findsNothing);
    expect(find.text('0/6'), findsOneWidget);
  });

  testWidgets('shows upload progress and a bilingual failure state', (
    tester,
  ) async {
    _useTallScreen(tester);
    final controller = ReviewOrderController()..setContent('服务很好');
    addTearDown(controller.dispose);
    final upload = Completer<String?>();
    await tester.pumpWidget(
      _testApp(
        ReviewOrderPage(
          order: sampleOrder(),
          controller: controller,
          onPickImage: () => upload.future,
        ),
        locale: const Locale('zh'),
      ),
    );

    await tester.tap(find.byKey(const Key('review-image-add-button')));
    await tester.pump();
    expect(find.text('上传中'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(
            find.byKey(const Key('review-submit-button')),
          )
          .onPressed,
      isNull,
    );

    upload.completeError(StateError('network unavailable'));
    await tester.pumpAndSettle();
    expect(find.text('图片上传失败，请重试。'), findsOneWidget);
    expect(find.text('最多可上传 6 张图片。'), findsOneWidget);
  });

  testWidgets('stops offering add after six image uploads', (tester) async {
    _useTallScreen(tester);
    final controller = ReviewOrderController();
    addTearDown(controller.dispose);
    var image = 0;
    await tester.pumpWidget(
      _testApp(
        ReviewOrderPage(
          order: sampleOrder(),
          controller: controller,
          onPickImage: () async => 'https://img/${++image}.jpg',
        ),
      ),
    );

    for (var count = 0; count < 6; count++) {
      await tester.tap(find.byKey(const Key('review-image-add-button')));
      await tester.pumpAndSettle();
    }

    expect(find.text('6/6'), findsOneWidget);
    expect(find.byKey(const Key('review-image-add-button')), findsNothing);
  });

  testWidgets(
      'review summary translates without translating or mutating edited draft',
      (tester) async {
    _useTallScreen(tester);
    final translations = RecordingTranslationRepository();
    final autoController = _activeAutoController(translations);
    addTearDown(autoController.dispose);
    final navigatorKey = GlobalKey<NavigatorState>();
    await tester.pumpWidget(
      AutoTranslationScope(
        controller: autoController,
        enabled: true,
        targetLanguage: 'en-US',
        child: MaterialApp(
          navigatorKey: navigatorKey,
          locale: const Locale('zh'),
          supportedLocales: const [Locale('en'), Locale('zh')],
          localizationsDelegates: GlobalMaterialLocalizations.delegates,
          home: const Scaffold(body: SizedBox.shrink()),
        ),
      ),
    );
    final result = navigatorKey.currentState!.push<ReviewDraft>(
      MaterialPageRoute(
        builder: (_) => ReviewOrderPage(
          order: sampleOrder(),
          initialReview: const Review(
            id: 'review-1',
            orderId: 'order-1',
            userId: 'user-1',
            rating: 4,
            content: '原始中文评价',
            tags: ['初始标签'],
            images: ['https://cdn.example/original.jpg'],
          ),
          onPickImage: () async => null,
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(_mountedRequests(tester), const {
      ('project', 'order:order-1', 'projectName', '光子嫩肤'),
      ('institution', 'order:order-1', 'institutionName', '娇颜颂医疗美容'),
    });
    expect(
        translations.calls.map((call) => call.text), isNot(contains('原始中文评价')));
    expect(
        translations.calls.map((call) => call.text), isNot(contains('初始标签')));

    await tester.enterText(
      find.byKey(const Key('review-content-field')),
      '编辑后中文评价',
    );
    await tester.enterText(
      find.byKey(const Key('review-tags-field')),
      '效果自然 服务细致',
    );
    await tester.tap(find.byKey(const Key('review-rating-5')));
    await tester.tap(find.byKey(const Key('review-submit-button')));
    await tester.pumpAndSettle();
    final draft = await result;

    expect(draft?.content, '编辑后中文评价');
    expect(draft?.tags, ['效果自然', '服务细致']);
    expect(draft?.rating, 5);
    expect(draft?.images, ['https://cdn.example/original.jpg']);
    for (final source in const [
      '原始中文评价',
      '初始标签',
      '编辑后中文评价',
      '效果自然',
      '服务细致',
    ]) {
      expect(
          translations.calls.map((call) => call.text), isNot(contains(source)));
    }
    expect(
      translations.calls.map((call) => call.text),
      ['光子嫩肤', '娇颜颂医疗美容'],
    );
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

void _useTallScreen(WidgetTester tester) {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Widget _testApp(Widget home, {Locale locale = const Locale('en')}) {
  return MaterialApp(
    locale: locale,
    supportedLocales: const [Locale('en'), Locale('zh')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    home: home,
  );
}
