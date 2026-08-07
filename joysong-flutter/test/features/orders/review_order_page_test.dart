import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/orders/presentation/order_detail_page.dart';
import 'package:joysong_flutter/features/orders/presentation/review_order_controller.dart';

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
