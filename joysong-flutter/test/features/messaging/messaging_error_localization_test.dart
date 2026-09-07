import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_controllers.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_pages.dart';

void main() {
  for (final customerService in [false, true]) {
    for (final loadOlder in [false, true]) {
      testWidgets(
          'localized loading failure: service=$customerService older=$loadOlder',
          (tester) async {
        final repository = _Repository(failInitial: !loadOlder);
        final dm = DmThreadController(
          repository: repository,
          conversationId: 'thread',
          pageSize: 1,
        );
        final service = CustomerServiceThreadController(
          repository: repository,
          conversationId: 'thread',
          pageSize: 1,
        );
        addTearDown(dm.dispose);
        addTearDown(service.dispose);
        const me = MessagingPeer(id: 'me', name: 'Me');
        const other = MessagingPeer(id: 'other', name: 'Other');
        final Widget page = customerService
            ? CustomerServiceThreadPage(
                controller: service,
                currentUserId: 'me',
                myPeer: me,
                otherPeer: other,
              )
            : DmThreadPage(
                controller: dm,
                currentUserId: 'me',
                myPeer: me,
                otherPeer: other,
              );
        await tester.pumpWidget(_app(page, 'en'));
        await tester.pumpAndSettle();
        if (loadOlder) {
          await tester.tap(find.text('Load earlier messages'));
          await tester.pumpAndSettle();
          expect(find.text('Previous message'), findsOneWidget);
        }
        expect(find.text('Unable to load messages. Please try again.'),
            findsOneWidget);
        expect(find.text('加载失败，请手动重试'), findsNothing);

        await tester.pumpWidget(_app(page, 'zh'));
        await tester.pumpAndSettle();
        expect(find.text('加载失败，请手动重试'), findsOneWidget);
        await tester.pumpWidget(const SizedBox.shrink());
      });
    }
  }
}

Widget _app(Widget page, String language) => MaterialApp(
      locale: Locale(language),
      supportedLocales: const [Locale('en'), Locale('zh')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: page,
    );

class _Repository implements MessagingRepository {
  _Repository({required this.failInitial});
  final bool failInitial;

  @override
  Future<List<DmMessage>> getDmMessages(String conversationId,
      {int limit = 30, String? before}) async {
    if (failInitial || before != null) throw StateError('offline');
    return const [
      DmMessage(
        id: 'message',
        conversationId: 'thread',
        senderId: 'me',
        content: 'Previous message',
        messageType: 'TEXT',
        isRead: true,
        createdAt: '2026-09-06T00:00:00Z',
      ),
    ];
  }

  @override
  Future<List<CustomerServiceMessage>> getCustomerServiceMessages(
      String conversationId,
      {int limit = 50,
      String? before}) async {
    if (failInitial || before != null) throw StateError('offline');
    return const [
      CustomerServiceMessage(
        id: 'message',
        senderId: 'me',
        senderName: 'Me',
        content: 'Previous message',
        messageType: 'TEXT',
        isRead: true,
        createdAt: '2026-09-06T00:00:00Z',
      ),
    ];
  }

  @override
  Future<void> markDmConversationRead(String conversationId) async {}

  @override
  Future<void> markCustomerServiceConversationRead(
      String conversationId) async {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
