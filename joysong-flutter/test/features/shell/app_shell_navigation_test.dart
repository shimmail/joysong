import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/features/shell/presentation/app_shell.dart';

void main() {
  testWidgets('system back pops nested content before leaving the app shell',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () => Navigator.of(context).push<void>(
              MaterialPageRoute(
                builder: (_) => const AppShell(agentConfig: AgentConfig()),
              ),
            ),
            child: const Text('open shell'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('open shell'));
    await tester.pumpAndSettle();

    final nestedNavigator = find.descendant(
      of: find.byType(AppShell),
      matching: find.byType(Navigator),
    );
    expect(nestedNavigator, findsOneWidget);
    final nestedNavigatorState = tester.state<NavigatorState>(nestedNavigator);
    nestedNavigatorState.push<void>(
      MaterialPageRoute(
        builder: (_) => const Scaffold(body: Text('nested detail')),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('nested detail'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.text('nested detail'), findsNothing);
    expect(find.byType(AppShell), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();

    expect(find.byType(AppShell), findsNothing);
    expect(find.text('open shell'), findsOneWidget);
  });
}
