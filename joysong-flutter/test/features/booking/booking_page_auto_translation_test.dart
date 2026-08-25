import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/translation/translation.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_controller.dart';
import 'package:joysong_flutter/features/booking/presentation/booking_page.dart';

import '../../core/translation/translation_test_fixtures.dart';
import 'booking_test_fixtures.dart';

void main() {
  testWidgets(
    'opted-in booking translates project institution and doctor title only',
    (tester) async {
      final translations = RecordingTranslationRepository();
      final autoController = _activeAutoController(translations);
      final controller = _bookingController();
      addTearDown(autoController.dispose);
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _host(
          autoController,
          BookingPage(
            controller: controller,
            onOrderCreated: (_) {},
            enableAutoTranslation: true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byType(DropdownButtonFormField<BookingDoctor>));
      await tester.pumpAndSettle();
      expect(
        _mountedRequests(tester),
        containsAll(const {
          ('project', 'institution-project:ip-1', 'name', '光子嫩肤'),
          ('institution', 'institution:institution-1', 'name', '娇颜颂医疗美容'),
          ('doctor', 'doctor:doctor-1', 'title', '主任医师'),
        }),
      );
      expect(find.text('en-US:光子嫩肤'), findsOneWidget);
      expect(find.text('en-US:娇颜颂医疗美容'), findsOneWidget);
      expect(find.text('张医生 · en-US:主任医师'), findsOneWidget);
      expect(
          translations.calls.map((call) => call.text), isNot(contains('张医生')));
      expect(
        translations.calls.map((call) => call.text),
        isNot(contains('李咨询师')),
      );

      await tester.tapAt(const Offset(4, 4));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.byKey(const Key('booking-remark-field')),
        260,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.enterText(
        find.byKey(const Key('booking-remark-field')),
        '请在术前电话联系我',
      );
      await tester.pump();
      expect(
        translations.calls.map((call) => call.text),
        isNot(contains('请在术前电话联系我')),
      );
    },
  );

  testWidgets('booking defaults off under an active scope', (tester) async {
    final translations = RecordingTranslationRepository();
    final autoController = _activeAutoController(translations);
    final controller = _bookingController();
    addTearDown(autoController.dispose);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _host(
        autoController,
        BookingPage(controller: controller, onOrderCreated: (_) {}),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('光子嫩肤'), findsOneWidget);
    expect(translations.calls, isEmpty);
    expect(find.byType(AutoTranslationBuilder), findsNothing);
  });

  testWidgets('booking failures preserve source text', (tester) async {
    final translations = RecordingTranslationRepository()
      ..failuresRemaining = 10;
    final autoController = _activeAutoController(translations);
    final controller = _bookingController();
    addTearDown(autoController.dispose);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _host(
        autoController,
        BookingPage(
          controller: controller,
          onOrderCreated: (_) {},
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<BookingDoctor>));
    await tester.pumpAndSettle();

    expect(find.text('光子嫩肤'), findsOneWidget);
    expect(find.text('娇颜颂医疗美容'), findsOneWidget);
    expect(find.text('张医生 · 主任医师'), findsWidgets);
    expect(translations.calls, hasLength(greaterThanOrEqualTo(3)));
  });

  testWidgets('booking empty and duplicate doctor IDs remain source-only',
      (tester) async {
    final repository = FakeBookingRepository()
      ..doctorResults = const [
        BookingDoctor(
          id: ' ',
          name: '空身份医生',
          title: '空身份职称',
          avatar: '',
          specialties: '皮肤美容',
          isVerified: true,
        ),
        BookingDoctor(
          id: 'duplicate-doctor',
          name: '重复医生甲',
          title: '重复职称甲',
          avatar: '',
          specialties: '皮肤美容',
          isVerified: true,
        ),
        BookingDoctor(
          id: 'duplicate-doctor',
          name: '重复医生乙',
          title: '重复职称乙',
          avatar: '',
          specialties: '皮肤美容',
          isVerified: true,
        ),
      ];
    final translations = RecordingTranslationRepository();
    final autoController = _activeAutoController(translations);
    final controller = _bookingController(repository: repository);
    addTearDown(autoController.dispose);
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      _host(
        autoController,
        BookingPage(
          controller: controller,
          onOrderCreated: (_) {},
          enableAutoTranslation: true,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<BookingDoctor>));
    await tester.pumpAndSettle();

    expect(find.text('空身份医生 · 空身份职称'), findsOneWidget);
    expect(find.text('重复医生甲 · 重复职称甲'), findsOneWidget);
    expect(find.text('重复医生乙 · 重复职称乙'), findsOneWidget);
    expect(
      translations.calls
          .map((call) => call.text)
          .where(const {'空身份职称', '重复职称甲', '重复职称乙'}.contains),
      isEmpty,
    );
    expect(
      _mountedRequests(tester).where((request) => request.$1 == 'doctor'),
      isEmpty,
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

BookingController _bookingController({FakeBookingRepository? repository}) =>
    BookingController(
      repository: repository ?? FakeBookingRepository(),
      institutionId: 'institution-1',
      projectId: 'project-1',
    );

AutoTranslationController _activeAutoController(
  TranslationRepository repository,
) {
  final controller = AutoTranslationController(repository: repository);
  controller.synchronize(
    enabled: true,
    authenticated: true,
    targetLanguage: 'en-US',
  );
  return controller;
}

Widget _host(AutoTranslationController controller, Widget child) =>
    AutoTranslationScope(
      controller: controller,
      enabled: true,
      targetLanguage: 'en-US',
      child: MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('en'), Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: child,
      ),
    );
