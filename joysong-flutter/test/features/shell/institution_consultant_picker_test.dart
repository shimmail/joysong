import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/booking/domain/booking_models.dart';
import 'package:joysong_flutter/features/shell/presentation/institution_consultant_picker.dart';

void main() {
  testWidgets('shows loading and every consultant in a scrollable list', (
    tester,
  ) async {
    final pending = Completer<List<BookingConsultant>>();

    await tester.pumpWidget(_pickerHost(loadConsultants: () => pending.future));
    await tester.tap(find.text('open'));
    await tester.pump();

    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    pending.complete(List.generate(
      25,
      (index) => BookingConsultant(id: 'c-$index', name: '顾问 $index'),
    ));
    await tester.pumpAndSettle();

    expect(find.text('顾问 0'), findsOneWidget);
    await tester.drag(
      find.byKey(const Key('institution-consultant-list')),
      const Offset(0, -1200),
    );
    await tester.pumpAndSettle();

    expect(find.text('顾问 24'), findsOneWidget);
  });

  testWidgets('shows the empty state when no consultants are available', (
    tester,
  ) async {
    await tester.pumpWidget(_pickerHost(loadConsultants: () async => const []));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.text('该机构暂无可咨询的咨询师'), findsOneWidget);
  });

  testWidgets('retries a failed consultant request and shows its rows', (
    tester,
  ) async {
    var calls = 0;
    Future<List<BookingConsultant>> loadConsultants() {
      calls += 1;
      if (calls == 1) return Future.error(StateError('request failed'));
      return Future.value(
        const [BookingConsultant(id: 'c-2', name: '顾问 二')],
      );
    }

    await tester.pumpWidget(_pickerHost(loadConsultants: loadConsultants));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.text('加载咨询师失败，请重试'), findsOneWidget);
    expect(find.text('重试'), findsOneWidget);

    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();

    expect(calls, 2);
    expect(find.text('顾问 二'), findsOneWidget);
  });

  testWidgets('shows retry state when the consultant request throws directly', (
    tester,
  ) async {
    await tester.pumpWidget(
      _pickerHost(
        loadConsultants: () => throw StateError('request failed directly'),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.text('加载咨询师失败，请重试'), findsOneWidget);
    expect(find.text('重试'), findsOneWidget);
  });

  testWidgets('returns the tapped consultant to the host', (tester) async {
    BookingConsultant? selected;
    final consultants = [
      const BookingConsultant(id: 'c-1', name: '顾问 一'),
      const BookingConsultant(id: 'c-2', name: '顾问 二'),
    ];

    await tester.pumpWidget(
      _pickerHost(
        loadConsultants: () async => consultants,
        onSelected: (consultant) => selected = consultant,
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('institution-consultant-c-2')));
    await tester.pumpAndSettle();

    expect(selected?.id, 'c-2');
  });

  testWidgets('returns null on dismissal without selecting a consultant', (
    tester,
  ) async {
    var selectionCalls = 0;
    var resultReturned = false;
    BookingConsultant? result;

    await tester.pumpWidget(
      _pickerHost(
        loadConsultants: () async => const [],
        onSelected: (_) => selectionCalls += 1,
        onResult: (value) {
          resultReturned = true;
          result = value;
        },
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    await tester.tapAt(const Offset(5, 5));
    await tester.pumpAndSettle();

    expect(resultReturned, isTrue);
    expect(result, isNull);
    expect(selectionCalls, 0);
  });
}

Widget _pickerHost({
  required Future<List<BookingConsultant>> Function() loadConsultants,
  ValueChanged<BookingConsultant>? onSelected,
  ValueChanged<BookingConsultant?>? onResult,
}) =>
    MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              final consultant = await showInstitutionConsultantPicker(
                context: context,
                loadConsultants: loadConsultants,
              );
              onResult?.call(consultant);
              if (consultant != null) onSelected?.call(consultant);
            },
            child: const Text('open'),
          ),
        ),
      ),
    );
