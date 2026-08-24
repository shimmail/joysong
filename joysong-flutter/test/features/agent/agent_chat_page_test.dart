import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_catalog_cards.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_chat_page.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_controller.dart';
import 'package:joysong_flutter/features/discover/domain/discover_models.dart';
import 'package:joysong_flutter/features/discover/domain/discover_repository.dart';
import 'package:joysong_flutter/features/shell/presentation/app_shell.dart';

void main() {
  testWidgets('long pressing a message bubble opens copy menu', (tester) async {
    await _pumpPage(tester, _CatalogRepository(_turn()));
    await _sendAndSettle(tester);

    await tester.longPress(find.text('参考结果'));
    await tester.pumpAndSettle();
    expect(find.text('复制'), findsOneWidget);

    await tester.tap(find.text('复制'));
    await tester.pump();

    expect((await Clipboard.getData(Clipboard.kTextPlain))?.text, '参考结果');
    expect(find.text('消息已复制'), findsOneWidget);
  });

  testWidgets('assistant bubble updates while the response is streaming',
      (tester) async {
    final stream = StreamController<AgentStreamEvent>();
    await _pumpPage(tester, _CatalogRepository.streams([stream]));

    await tester.enterText(find.byType(TextField), '推荐项目');
    await tester.tap(find.byIcon(Icons.send));
    await tester.pump();
    expect(find.text('正在思考…'), findsOneWidget);

    stream.add(const AgentStreamDelta(content: '实时内容'));
    await tester.pump();
    expect(find.text('实时内容'), findsOneWidget);

    stream.add(AgentStreamCompleted(turn: _turn()));
    await stream.close();
    await tester.pumpAndSettle();
  });

  testWidgets('interrupted stream offers retry without duplicating user bubble',
      (tester) async {
    final first = StreamController<AgentStreamEvent>();
    final second = StreamController<AgentStreamEvent>();
    await _pumpPage(tester, _CatalogRepository.streams([first, second]));

    await tester.enterText(find.byType(TextField), '推荐项目');
    await tester.tap(find.byIcon(Icons.send));
    await tester.pump();
    first
      ..add(const AgentStreamDelta(content: '部分内容'))
      ..add(const AgentStreamFailed(
        code: 'UPSTREAM_TIMEOUT',
        traceId: 'trace-1',
        retryable: true,
      ));
    await first.close();
    await tester.pumpAndSettle();

    expect(find.text('部分内容'), findsOneWidget);
    expect(find.text('生成中断，可重试'), findsOneWidget);
    expect(find.text('推荐项目'), findsOneWidget);

    await tester.tap(find.text('生成中断，可重试'));
    await tester.pump();
    expect(find.text('推荐项目'), findsOneWidget);
    expect(find.text('部分内容'), findsNothing);

    second.add(AgentStreamCompleted(turn: _turn()));
    await second.close();
    await tester.pumpAndSettle();
    expect(find.text('推荐项目'), findsOneWidget);
    expect(find.text('参考结果'), findsOneWidget);
  });

  testWidgets('non-retryable interruption shows status without retry action',
      (tester) async {
    final stream = StreamController<AgentStreamEvent>();
    await _pumpPage(tester, _CatalogRepository.streams([stream]));
    await tester.enterText(find.byType(TextField), '推荐项目');
    await tester.tap(find.byIcon(Icons.send));
    await tester.pump();
    stream
      ..add(const AgentStreamDelta(content: '部分内容'))
      ..add(const AgentStreamFailed(
        code: 'POLICY_REJECTED',
        traceId: null,
        retryable: false,
      ));
    await stream.close();
    await tester.pumpAndSettle();

    expect(find.text('部分内容'), findsOneWidget);
    expect(find.text('生成中断'), findsOneWidget);
    expect(find.text('生成中断，可重试'), findsNothing);
  });

  testWidgets(
      'incomplete comparison shows status card and suppresses catalog links',
      (tester) async {
    final repository = _CatalogRepository(
      _turn(
        catalogItems: const [_project],
        comparisonRequest: _incompleteRequest,
      ),
    );

    await _pumpPage(tester, repository);
    await _sendAndSettle(tester);

    expect(find.byType(AgentComparisonStatusCard), findsOneWidget);
    expect(find.byType(AgentCatalogReportCard), findsNothing);
    expect(find.byType(AgentCatalogLinkCard), findsNothing);
  });

  testWidgets(
      'complete comparison shows message-bound report and suppresses duplicate links',
      (tester) async {
    final repository = _CatalogRepository(
      _turn(
        catalogItems: const [_project],
        comparisonRequest: _completeRequest,
        messageReport: _comparisonReport,
      ),
    );

    await _pumpPage(tester, repository);
    await _sendAndSettle(tester);

    expect(find.byType(AgentComparisonStatusCard), findsNothing);
    expect(find.byType(AgentCatalogReportCard), findsOneWidget);
    expect(find.byType(AgentComparisonTable), findsOneWidget);
    expect(find.byType(AgentCatalogDetailCard), findsNothing);
    expect(find.byType(AgentCatalogLinkCard), findsNothing);
  });

  testWidgets('complete comparison without report suppresses all attachments',
      (tester) async {
    final repository = _CatalogRepository(
      _turn(
        catalogItems: const [_project],
        comparisonRequest: _completeRequest,
      ),
    );

    await _pumpPage(tester, repository);
    await _sendAndSettle(tester);

    expect(find.byType(AgentComparisonStatusCard), findsNothing);
    expect(find.byType(AgentCatalogReportCard), findsNothing);
    expect(find.byType(AgentCatalogLinkCard), findsNothing);
  });

  testWidgets('ordinary catalog message still shows lightweight links',
      (tester) async {
    AgentCatalogItem? opened;
    final repository = _CatalogRepository(
      _turn(catalogItems: const [_project]),
    );

    await _pumpPage(tester, repository, onOpen: (item) => opened = item);
    await _sendAndSettle(tester);

    expect(find.byType(AgentComparisonStatusCard), findsNothing);
    expect(find.byType(AgentCatalogReportCard), findsNothing);
    expect(find.byType(AgentCatalogLinkCard), findsOneWidget);
    await tester.tap(find.byType(AgentCatalogLinkCard));
    expect(opened?.id, 'project-1');
  });

  testWidgets(
      'ordinary institution consultation forwards the safe institution id',
      (tester) async {
    String? consultedInstitutionId;
    final repository = _CatalogRepository(
      _turn(catalogItems: const [_institution]),
    );

    await _pumpPage(
      tester,
      repository,
      onHumanConsult: (institutionId) {
        consultedInstitutionId = institutionId;
      },
    );
    await _sendAndSettle(tester);

    final action = find.byKey(
      const ValueKey(
        'agent-human-consult-INSTITUTION-institution-record-1',
      ),
    );
    expect(action, findsOneWidget);
    await tester.ensureVisible(action);
    await tester.tap(action);

    expect(consultedInstitutionId, 'institution-safe-1');
  });

  testWidgets('comparison report consultation forwards the safe institution id',
      (tester) async {
    String? consultedInstitutionId;
    final repository = _CatalogRepository(
      _turn(
        catalogItems: const [_institution],
        comparisonRequest: _institutionComparisonRequest,
        messageReport: _institutionComparisonReport,
      ),
    );

    await _pumpPage(
      tester,
      repository,
      onHumanConsult: (institutionId) {
        consultedInstitutionId = institutionId;
      },
    );
    await _sendAndSettle(tester);

    final action = find.byKey(
      const ValueKey(
        'agent-human-consult-INSTITUTION-institution-record-1',
      ),
    );
    expect(action, findsOneWidget);
    await tester.ensureVisible(action);
    await tester.tap(action);

    expect(consultedInstitutionId, 'institution-safe-1');
  });

  testWidgets('restored institution card remains actionable', (tester) async {
    String? consultedInstitutionId;
    final repository = _CatalogRepository.restored(
      const [_restoredInstitutionMessage],
    );

    await _pumpPage(
      tester,
      repository,
      onHumanConsult: (institutionId) {
        consultedInstitutionId = institutionId;
      },
    );

    final action = find.byKey(
      const ValueKey(
        'agent-human-consult-INSTITUTION-institution-record-1',
      ),
    );
    expect(action, findsOneWidget);
    await tester.ensureVisible(action);
    await tester.tap(action);

    expect(consultedInstitutionId, 'institution-safe-1');
  });

  testWidgets('unknown catalog type is hidden and cannot navigate',
      (tester) async {
    final repository = _CatalogRepository(
      _turn(catalogItems: const [_unknown]),
    );
    var openCalls = 0;

    await _pumpPage(tester, repository, onOpen: (_) => openCalls++);
    await _sendAndSettle(tester);

    expect(find.byType(AgentCatalogLinkCard), findsNothing);
    expect(find.text('查看详情'), findsNothing);
    expect(openCalls, 0);
  });

  testWidgets(
      'doctor catalog record id is never forwarded as a consultation target',
      (tester) async {
    final repository = _CatalogRepository(
      _turn(catalogItems: const [_doctor]),
    );
    var consultCalls = 0;

    await _pumpPage(
      tester,
      repository,
      onHumanConsult: (_) => consultCalls++,
    );
    await _sendAndSettle(tester);

    expect(
      find.byKey(
        const ValueKey('agent-human-consult-DOCTOR-doctor-record-1'),
      ),
      findsNothing,
    );
    expect(consultCalls, 0);
  });

  test('catalog detail adapter never exposes a doctor consultation callback',
      () {
    final page = buildAgentCatalogDetailPage(
      repository: _DiscoverRepository(),
      type: DiscoverContentType.doctor,
      id: 'doctor-record-1',
    );

    expect(page.onConsultDoctor, isNull);
  });
}

Future<void> _pumpPage(
  WidgetTester tester,
  AgentRepository repository, {
  ValueChanged<AgentCatalogItem>? onOpen,
  AgentHumanConsultationAction? onHumanConsult,
}) async {
  final chatController = AgentChatController(repository: repository);
  final planController = AgentPlanController(repository);
  addTearDown(chatController.dispose);
  addTearDown(planController.dispose);
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
        onOpenCatalogItem: onOpen,
        onHumanConsult: onHumanConsult,
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _sendAndSettle(WidgetTester tester) async {
  await tester.enterText(find.byType(TextField), '推荐项目');
  await tester.tap(find.byIcon(Icons.send));
  await tester.pumpAndSettle();
}

class _CatalogRepository extends Fake implements AgentRepository {
  _CatalogRepository(this.turn)
      : streams = const [],
        sessions = const [],
        messages = const [];

  _CatalogRepository.streams(this.streams)
      : turn = null,
        sessions = const [],
        messages = const [];

  _CatalogRepository.restored(this.messages)
      : turn = null,
        streams = const [],
        sessions = const [_session];

  final ChatTurn? turn;
  final List<StreamController<AgentStreamEvent>> streams;
  final List<ChatSession> sessions;
  final List<ChatMessage> messages;
  int streamCalls = 0;

  @override
  Stream<AgentStreamEvent> streamMessage({
    required String sessionId,
    required String content,
    required String idempotencyKey,
  }) =>
      streams.isEmpty
          ? Stream.value(AgentStreamCompleted(turn: turn!))
          : streams[streamCalls++].stream;

  @override
  Future<List<ChatSession>> getSessions({ChatPersona? persona}) async =>
      sessions;

  @override
  Future<List<ChatMessage>> getMessages(
    String sessionId, {
    int limit = 30,
    String? before,
  }) async =>
      messages;

  @override
  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    String contextId = '',
    String title = '',
  }) async =>
      _session;

  @override
  Future<ChatTurn> sendMessage(
    String sessionId,
    String content, {
    required String idempotencyKey,
  }) async =>
      turn!;
}

ChatTurn _turn({
  AgentCatalogReport? report,
  AgentCatalogReport? messageReport,
  AgentComparisonRequest? comparisonRequest,
  List<AgentCatalogItem> catalogItems = const [],
}) =>
    ChatTurn(
      message: ChatMessage(
        id: 'assistant-1',
        sessionId: 'session-1',
        role: 'ASSISTANT',
        content: '参考结果',
        createdAt: '2026-08-11T10:01:00',
        catalogItems: catalogItems,
        comparisonRequest: comparisonRequest,
        catalogReport: messageReport,
      ),
      catalogReport: report,
      catalogItems: catalogItems,
      intent: 'CATALOG_QUERY',
      queryTarget: null,
      nextAction: 'NONE',
    );

const _session = ChatSession(
  id: 'session-1',
  persona: 'CONSULTANT',
  contextType: 'GENERAL',
  contextId: '',
  title: '会话',
  lastMessage: '',
  createdAt: '2026-08-11T10:00:00',
  updatedAt: '2026-08-11T10:00:00',
);

const _project = AgentCatalogItem(
  type: 'PROJECT',
  id: 'project-1',
  name: '项目一',
  subtitle: '',
  summary: '',
  attributes: {},
  institutionId: null,
  projectId: null,
  canChatWithHuman: false,
);

const _institution = AgentCatalogItem(
  type: 'INSTITUTION',
  id: 'institution-record-1',
  name: '机构一',
  subtitle: '上海',
  summary: '',
  attributes: {'City': '上海'},
  institutionId: 'institution-safe-1',
  projectId: null,
  canChatWithHuman: true,
);

const _doctor = AgentCatalogItem(
  type: 'DOCTOR',
  id: 'doctor-record-1',
  name: '医生一',
  subtitle: '',
  summary: '',
  attributes: {},
  institutionId: 'institution-must-not-leak',
  projectId: null,
  canChatWithHuman: true,
);

const _unknown = AgentCatalogItem(
  type: 'ARTICLE',
  id: 'article-1',
  name: '未知引用',
  subtitle: '',
  summary: '',
  attributes: {},
  institutionId: null,
  projectId: null,
  canChatWithHuman: false,
);

const _incompleteRequest = AgentComparisonRequest(
  operands: [
    AgentComparisonOperand(
      entityType: 'PROJECT',
      entityId: 'project-1',
      displayName: '项目一',
    ),
  ],
  targetType: 'PROJECT',
  dimensions: [],
  constraints: {},
  missingFields: {'OPERANDS'},
);

const _completeRequest = AgentComparisonRequest(
  operands: [
    AgentComparisonOperand(
      entityType: 'PROJECT',
      entityId: 'project-1',
      displayName: '项目一',
    ),
    AgentComparisonOperand(
      entityType: 'PROJECT',
      entityId: 'project-2',
      displayName: '项目二',
    ),
  ],
  targetType: 'PROJECT',
  dimensions: ['PRICE'],
  constraints: {},
  missingFields: {},
);

const _institutionComparisonRequest = AgentComparisonRequest(
  operands: [
    AgentComparisonOperand(
      entityType: 'INSTITUTION',
      entityId: 'institution-record-1',
      displayName: '机构一',
    ),
    AgentComparisonOperand(
      entityType: 'INSTITUTION',
      entityId: 'institution-record-2',
      displayName: '机构二',
    ),
  ],
  targetType: 'INSTITUTION',
  dimensions: ['CITY'],
  constraints: {},
  missingFields: {},
);

const _comparisonReport = AgentCatalogReport(
  mode: 'COMPARISON',
  title: '项目对比',
  summary: '',
  items: [_project],
  comparisonDimensions: ['Reference price'],
  warnings: [],
);

const _institutionComparisonReport = AgentCatalogReport(
  mode: 'COMPARISON',
  title: '机构对比',
  summary: '',
  items: [_institution],
  comparisonDimensions: ['City'],
  warnings: [],
);

const _restoredInstitutionMessage = ChatMessage(
  id: 'assistant-restored',
  sessionId: 'session-1',
  role: 'ASSISTANT',
  content: '请选择希望咨询的机构。',
  createdAt: '2026-08-11T10:01:00',
  catalogItems: [_institution],
);

class _DiscoverRepository extends Fake implements DiscoverRepository {}
