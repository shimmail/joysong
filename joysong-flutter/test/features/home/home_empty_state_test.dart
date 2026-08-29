import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/home/domain/home_models.dart';
import 'package:joysong_flutter/features/home/domain/home_repository.dart';
import 'package:joysong_flutter/features/home/presentation/home_page.dart';

void main() {
  testWidgets(
    'empty home keeps the greeting and message entry available',
    (tester) async {
      var messagesOpened = false;

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('en'),
          home: HomePage(
            repository: const _EmptyHomeRepository(),
            onNotifications: () => messagesOpened = true,
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Hello! ...'), findsOneWidget);
      expect(
        find.text('No recommendations yet. Pull down to refresh.'),
        findsOneWidget,
      );

      final messageEntry = find.byTooltip('Messages');
      expect(messageEntry, findsOneWidget);
      await tester.tap(messageEntry);
      await tester.pump();

      expect(messagesOpened, isTrue);
    },
  );
}

final class _EmptyHomeRepository implements HomeRepository {
  const _EmptyHomeRepository();

  @override
  Future<HomeFeed> loadHome() async => const HomeFeed();
}
