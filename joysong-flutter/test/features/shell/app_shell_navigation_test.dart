import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/config/app_environment.dart';
import 'package:joysong_flutter/core/network/api_client.dart';
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

  testWidgets(
      'agent institution handoff opens the selected consultant direct message',
      (tester) async {
    final client = _AgentHandoffApiClient();
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        localizationsDelegates: GlobalMaterialLocalizations.delegates,
        home: AppShell(
          agentConfig: const AgentConfig(),
          apiClient: client,
          currentUserId: 'user-1',
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('消息'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('message-center-yanyan')));
    await tester.pumpAndSettle();

    final consultationAction = find.byKey(
      const ValueKey('agent-human-consult-INSTITUTION-institution-1'),
    );
    expect(consultationAction, findsOneWidget);
    await tester.ensureVisible(consultationAction);
    await tester.tap(consultationAction);
    await tester.pumpAndSettle();

    final consultant = find.byKey(
      const ValueKey('institution-consultant-consultant-2'),
    );
    expect(consultant, findsOneWidget);
    await tester.tap(consultant);
    await tester.pumpAndSettle();

    final dmPost = client.posts.singleWhere(
      (request) => request.$1 == 'dm/conversations',
    );
    expect(dmPost.$2, {'targetId': 'consultant-2'});
  });
}

final class _AgentHandoffApiClient extends ApiClient {
  _AgentHandoffApiClient()
      : super(
          apiRoot: Uri.parse('http://localhost/api/'),
          httpClient: _TestHttpClient(),
        );

  final posts = <(String, Object?)>[];

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    final Object data = switch (path) {
      'notifications/unread-count' => 0,
      'notifications' ||
      'dm/conversations' ||
      'cs/conversations' =>
        const <Object?>[],
      '/discover/filter-options' => const <String, Object?>{
          'categories': <String>[],
          'tags': <String>[],
          'cities': <String>[],
        },
      'chat/sessions' => const <Object?>[_agentSessionJson],
      'chat/sessions/session-human/messages' => const <Object?>[
          _agentInstitutionMessageJson
        ],
      'discover/institutions/institution-1/consultants' => const <Object?>[
          <String, Object?>{'id': 'consultant-2', 'name': '林顾问'},
        ],
      _ => const <Object?>[],
    };
    return decodeData(data);
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    posts.add((path, body));
    if (path == 'dm/conversations') return decodeData(_dmConversationJson);
    return decodeData(const <String, Object?>{});
  }

  @override
  Future<T?> put<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async =>
      decodeData(null);
}

final class _TestHttpClient extends Fake implements HttpClient {}

const _agentSessionJson = <String, Object?>{
  'id': 'session-human',
  'persona': 'CONSULTANT',
  'contextType': 'GENERAL',
  'contextId': '',
  'title': '真人咨询',
  'lastMessage': '请选择希望咨询的机构。',
  'createdAt': '2026-08-17T09:00:00Z',
  'updatedAt': '2026-08-17T09:01:00Z',
};

const _agentInstitutionMessageJson = <String, Object?>{
  'id': 'assistant-human',
  'sessionId': 'session-human',
  'role': 'ASSISTANT',
  'content': '请选择希望咨询的机构，随后可查看当前可联系的咨询师。',
  'createdAt': '2026-08-17T09:01:00Z',
  'catalogItems': <Object?>[
    <String, Object?>{
      'type': 'INSTITUTION',
      'id': 'institution-1',
      'name': '机构一',
      'subtitle': '上海',
      'summary': '',
      'attributes': <String, String>{'City': '上海'},
      'institutionId': 'institution-1',
      'projectId': null,
      'canChatWithHuman': true,
    },
  ],
  'comparisonRequest': null,
  'catalogReport': null,
};

const _dmConversationJson = <String, Object?>{
  'id': 'dm-human',
  'userAId': 'user-1',
  'userBId': 'consultant-2',
  'lastMessage': null,
  'lastMessageAt': null,
  'userAUnread': 0,
  'userBUnread': 0,
  'createdAt': '2026-08-17T09:02:00Z',
  'updatedAt': '2026-08-17T09:02:00Z',
  'firstMessageLimitApplies': false,
  'waitingForReply': false,
};
