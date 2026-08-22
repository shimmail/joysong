import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_controllers.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_pages.dart';

void main() {
  testWidgets(
      'read-only order history hides composer image send and removal actions',
      (tester) async {
    final repository = _ThreadRepository();
    final controller = DmThreadController(
      repository: repository,
      conversationId: 'conversation-order-1',
      currentUserId: 'user-1',
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        home: DmThreadPage(
          controller: controller,
          currentUserId: 'user-1',
          myPeer: _me,
          otherPeer: _consultant,
          conversationType: DmConversationType.orderService,
          sendEnabled: false,
          onPickImage: () async => 'https://cdn.example/image.jpg',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Retained service history'), findsOneWidget);
    expect(find.byType(TextField), findsNothing);
    expect(find.byIcon(Icons.add_photo_alternate_outlined), findsNothing);
    expect(find.byIcon(Icons.send), findsNothing);

    await tester.longPress(find.text('Retained service history'));
    await tester.pumpAndSettle();

    expect(find.text('Copy'), findsOneWidget);
    expect(find.text('Unsend'), findsNothing);
    expect(find.text('Delete'), findsNothing);
  });

  testWidgets('DIRECT thread keeps the default composer and removal actions',
      (tester) async {
    final repository = _ThreadRepository();
    final controller = DmThreadController(
      repository: repository,
      conversationId: 'conversation-direct-1',
      currentUserId: 'user-1',
    );
    addTearDown(controller.dispose);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en'),
        home: DmThreadPage(
          controller: controller,
          currentUserId: 'user-1',
          myPeer: _me,
          otherPeer: _consultant,
          onPickImage: () async => 'https://cdn.example/image.jpg',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(TextField), findsOneWidget);
    expect(find.byIcon(Icons.add_photo_alternate_outlined), findsOneWidget);
    expect(find.byIcon(Icons.send), findsOneWidget);

    await tester.longPress(find.text('Retained service history'));
    await tester.pumpAndSettle();

    expect(find.text('Unsend'), findsOneWidget);
    expect(find.text('Delete'), findsOneWidget);
  });
}

final class _ThreadRepository extends Fake implements MessagingRepository {
  @override
  Future<List<DmMessage>> getDmMessages(
    String conversationId, {
    int limit = 30,
    String? before,
  }) async =>
      const [_message];

  @override
  Future<void> markDmConversationRead(String conversationId) async {}

  @override
  Future<void> deleteDmMessage(String messageId) async {}
}

const _me = MessagingPeer(id: 'user-1', name: 'Me');
const _consultant = MessagingPeer(id: 'consultant-1', name: 'Consultant');
const _message = DmMessage(
  id: 'message-1',
  conversationId: 'conversation-order-1',
  senderId: 'user-1',
  content: 'Retained service history',
  messageType: 'TEXT',
  isRead: true,
  createdAt: '2026-08-21T10:05:00',
);
