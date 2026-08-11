import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_page.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_controller.dart';

void main() {
  testWidgets('session mutation actions are disabled during a REST send',
      (tester) async {
    tester.view
      ..physicalSize = const Size(1200, 800)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final pendingSend = Completer<ChatTurn>();
    final repository = _BusyAgentRepository(pendingSend);
    final chatController = AgentChatController(repository: repository);
    final planController = AgentPlanController(repository);

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: AgentChatPage(
          chatController: chatController,
          planController: planController,
        ),
      ),
    );
    await tester.pump();
    final send = chatController.send('想改善肤质');
    await tester.pump();
    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();

    final items = tester
        .widgetList<PopupMenuItem>(
          find.byWidgetPredicate((widget) => widget is PopupMenuItem),
        )
        .toList(growable: false);
    expect(items, hasLength(7));
    for (final index in [3, 4, 5, 6]) {
      expect(
        items[index].enabled,
        isFalse,
        reason: 'session mutation menu item $index must be disabled',
      );
    }

    pendingSend.complete(_turn);
    await send;
    await tester.pumpWidget(const SizedBox.shrink());
    chatController.dispose();
    planController.dispose();
  });

  testWidgets(
      'context initialization preserves an in-flight REST send without an async error',
      (tester) async {
    final pendingSend = Completer<ChatTurn>();
    final repository = _BusyAgentRepository(pendingSend);
    final chatController = AgentChatController(repository: repository);
    final planController = AgentPlanController(repository);
    addTearDown(() {
      if (!pendingSend.isCompleted) pendingSend.complete(_turn);
      chatController.dispose();
      planController.dispose();
    });
    final send = chatController.send('正在发送的问题');
    await tester.pump();
    expect(chatController.state.activeSession?.id, _session.id);

    await tester.pumpWidget(
      MaterialApp(
        home: AgentChatPage(
          chatController: chatController,
          planController: planController,
          initialContextType: ChatContextType.project,
          initialContextId: 'project-1',
          initialContextName: '项目一',
        ),
      ),
    );
    await tester.pump();
    await tester.pump();

    expect(tester.takeException(), isNull);
    expect(chatController.state.activeSession?.id, _session.id);
    expect(chatController.state.deliveryState, ChatDeliveryState.sending);

    pendingSend.complete(_turn);
    await send;
    await tester.pumpWidget(const SizedBox.shrink());
  });
}

class _BusyAgentRepository extends Fake implements AgentRepository {
  _BusyAgentRepository(this.pendingSend);

  final Completer<ChatTurn> pendingSend;

  @override
  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    String contextId = '',
    String title = '',
  }) async =>
      _session;

  @override
  Future<List<ChatSession>> getSessions({ChatPersona? persona}) async =>
      const [];

  @override
  Future<ChatTurn> sendMessage(String sessionId, String content) =>
      pendingSend.future;
}

const _session = ChatSession(
  id: 'session-1',
  persona: 'CONSULTANT',
  contextType: 'GENERAL',
  contextId: '',
  title: '会话',
  lastMessage: '',
  createdAt: '2026-08-06T10:00:00',
  updatedAt: '2026-08-06T10:00:00',
);

const _turn = ChatTurn(
  message: ChatMessage(
    id: 'assistant-1',
    sessionId: 'session-1',
    role: 'ASSISTANT',
    content: '完整答复',
    createdAt: '2026-08-06T10:01:00',
  ),
  catalogReport: null,
  catalogItems: [],
  intent: 'GENERAL_CHAT',
  queryTarget: null,
  nextAction: 'NONE',
);
