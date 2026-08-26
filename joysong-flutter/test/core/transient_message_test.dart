import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/transient_message.dart';

void main() {
  testWidgets('uses one two-second snackbar and dismisses it', (tester) async {
    await tester.pumpWidget(const _TransientMessageHarness());

    await tester.tap(find.byKey(const Key('first-message')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    final snackBar = tester.widget<SnackBar>(find.byType(SnackBar));
    expect(snackBar.duration, const Duration(seconds: 2));
    expect(find.text('First message'), findsOneWidget);

    await tester.pump(const Duration(seconds: 2));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(SnackBar), findsNothing);
  });

  testWidgets('replaces rapid messages and restarts the timer', (tester) async {
    await tester.pumpWidget(const _TransientMessageHarness());

    await tester.tap(find.byKey(const Key('first-message')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pump(const Duration(milliseconds: 1500));
    await tester.tap(find.byKey(const Key('latest-message')));
    await tester.tap(find.byKey(const Key('latest-message')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.text('First message'), findsNothing);
    expect(find.text('Latest message'), findsOneWidget);
    expect(find.byType(SnackBar), findsOneWidget);

    await tester.pump(const Duration(seconds: 2));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(SnackBar), findsNothing);
  });
}

class _TransientMessageHarness extends StatelessWidget {
  const _TransientMessageHarness();

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Builder(
        builder: (context) => Scaffold(
          body: Column(
            children: [
              ElevatedButton(
                key: const Key('first-message'),
                onPressed: () =>
                    showTransientMessage(context, 'First message'),
                child: const Text('First'),
              ),
              ElevatedButton(
                key: const Key('latest-message'),
                onPressed: () =>
                    showTransientMessage(context, 'Latest message'),
                child: const Text('Latest'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
