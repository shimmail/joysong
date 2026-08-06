import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_models.dart';
import 'package:joysong_flutter/features/messaging/domain/messaging_repository.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_controllers.dart';
import 'package:joysong_flutter/features/messaging/presentation/messaging_pages.dart';

void main() {
  testWidgets('empty English hub creates and opens customer service',
      (tester) async {
    final repository = _PageMessagingRepository();
    final controller = MessagingHubController(repository);
    CustomerServiceConversation? opened;

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en', 'US'),
        supportedLocales: const [Locale('zh', 'CN'), Locale('en', 'US')],
        home: MessagingCenterPage(
          controller: controller,
          onOpenCustomerService: (conversation) => opened = conversation,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Customer service'), findsWidgets);
    expect(find.text('Need help? Tap to start a chat.'), findsOneWidget);

    await tester.tap(find.byKey(const Key('customer-service-start')));
    await tester.pumpAndSettle();

    expect(repository.createCalls, 1);
    expect(opened?.id, _conversation.id);
    expect(find.byKey(const ValueKey('customer-service-cs-1')), findsOneWidget);
  });

  testWidgets('hub shows loading and localized creation failure',
      (tester) async {
    final repository = _PageMessagingRepository()
      ..conversationLoad = Completer<List<CustomerServiceConversation>>()
      ..failCreate = true;
    final controller = MessagingHubController(repository);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('en', 'US'),
        supportedLocales: const [Locale('zh', 'CN'), Locale('en', 'US')],
        home: MessagingCenterPage(controller: controller),
      ),
    );
    await tester.pump();
    expect(find.byKey(const Key('customer-service-loading')), findsOneWidget);

    repository.conversationLoad!.complete(const []);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('customer-service-start')));
    await tester.pumpAndSettle();

    expect(find.text('Something went wrong. Try again later.'), findsOneWidget);
    expect(repository.createCalls, 1);
  });
}

class _PageMessagingRepository extends Fake implements MessagingRepository {
  Completer<List<CustomerServiceConversation>>? conversationLoad;
  bool failCreate = false;
  int createCalls = 0;

  @override
  Future<List<DmConversation>> getDmConversations() async => const [];

  @override
  Future<List<CustomerServiceConversation>> getCustomerServiceConversations() =>
      conversationLoad?.future ??
      Future<List<CustomerServiceConversation>>.value(const []);

  @override
  Future<CustomerServiceConversation>
      createCustomerServiceConversation() async {
    createCalls++;
    if (failCreate) throw StateError('offline');
    return _conversation;
  }
}

const _conversation = CustomerServiceConversation(
  id: 'cs-1',
  userAId: 'me',
  userBId: 'support',
  lastMessage: null,
  lastMessageAt: null,
  unreadCount: 0,
  createdAt: '2026-08-06T10:00:00',
  updatedAt: '2026-08-06T10:00:00',
);
