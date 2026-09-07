import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_page.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_profile_safety_page.dart';

void main() {
  testWidgets('chat request failure updates when language changes',
      (tester) async {
    final repository = _Repository(failSessions: true);
    final chat = AgentChatController(repository: repository);
    final plan = AgentPlanController(repository);
    addTearDown(chat.dispose);
    addTearDown(plan.dispose);
    final page = AgentChatPage(chatController: chat, planController: plan);

    await tester.pumpWidget(_app(page, 'en'));
    await tester.pumpAndSettle();
    expect(find.text('Request failed. Please try again later.'), findsOneWidget);
    expect(find.text('请求失败，请稍后手动重试'), findsNothing);

    await tester.pumpWidget(_app(page, 'zh'));
    await tester.pumpAndSettle();
    expect(find.text('请求失败，请稍后手动重试'), findsOneWidget);
  });

  testWidgets('interrupted AI stream displays a localized error', (tester) async {
    final repository = _Repository();
    final chat = AgentChatController(repository: repository);
    final plan = AgentPlanController(repository);
    addTearDown(chat.dispose);
    addTearDown(plan.dispose);
    final page = AgentChatPage(chatController: chat, planController: plan);
    await tester.pumpWidget(_app(page, 'en'));
    await tester.pumpAndSettle();
    // Let stream delivery and cancellation complete outside the widget clock.
    await tester.runAsync(() async {
      await chat.openSession(const ChatSession(
        id: 'session',
        persona: 'CONSULTANT',
        contextType: 'GENERAL',
        contextId: '',
        title: '',
        lastMessage: '',
        createdAt: '',
        updatedAt: '',
      )).timeout(const Duration(seconds: 5));
      await chat.send('Hello').timeout(const Duration(seconds: 5));
    });
    await tester.pumpAndSettle();
    expect(find.text('Response generation was interrupted.'), findsOneWidget);
    expect(find.text('生成中断'), findsNothing);

    await tester.pumpWidget(_app(page, 'zh'));
    await tester.pumpAndSettle();
    expect(find.text('生成中断'), findsOneWidget);
  });

  testWidgets('profile load failure follows the current language', (tester) async {
    final plan = AgentPlanController(_Repository());
    addTearDown(plan.dispose);
    final page = AgentProfileSafetyPage(controller: plan);
    await tester.pumpWidget(_app(page, 'en'));
    await tester.pumpAndSettle();
    expect(find.text('Something went wrong. Please try again later.'),
        findsOneWidget);
    expect(find.text('操作失败，请稍后重试'), findsNothing);

    await tester.pumpWidget(_app(page, 'zh'));
    await tester.pumpAndSettle();
    expect(find.text('操作失败，请稍后重试'), findsOneWidget);
  });
}

Widget _app(Widget page, String language) => MaterialApp(
      locale: Locale(language),
      supportedLocales: const [Locale('en'), Locale('zh')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: page,
    );

class _Repository implements AgentRepository {
  _Repository({this.failSessions = false});
  final bool failSessions;

  @override
  Future<List<ChatSession>> getSessions({ChatPersona? persona}) async {
    if (failSessions) throw StateError('unavailable');
    return [];
  }

  @override
  Future<List<ChatMessage>> getMessages(String sessionId,
          {int limit = 30, String? before}) async =>
      [];

  @override
  Stream<AgentStreamEvent> streamMessage({
    required String sessionId,
    required String content,
    required String idempotencyKey,
  }) =>
      Stream.value(const AgentStreamFailed(
        code: 'INTERRUPTED',
        traceId: null,
        retryable: false,
      ));

  @override
  Future<AgentProfile> getProfile() async =>
      throw const FormatException('invalid');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}
