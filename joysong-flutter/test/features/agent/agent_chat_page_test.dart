import 'package:flutter/material.dart';
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
  testWidgets('latest catalog report is rendered once and opens its item',
      (tester) async {
    final repository = _CatalogRepository(
      _turn(report: _report, catalogItems: const [_duplicateProject]),
    );
    AgentCatalogItem? opened;

    await _pumpPage(tester, repository, onOpen: (item) => opened = item);
    await _sendAndSettle(tester);

    expect(find.byType(AgentCatalogReportCard), findsOneWidget);
    expect(find.byType(AgentCatalogDetailCard), findsOneWidget);
    expect(find.text('重复项目'), findsNothing);
    await tester.tap(find.text('查看详情'));
    expect(opened?.id, 'project-1');
  });

  testWidgets('catalog items render when no report is available',
      (tester) async {
    final repository = _CatalogRepository(
      _turn(catalogItems: const [_project]),
    );

    await _pumpPage(tester, repository);
    await _sendAndSettle(tester);

    expect(find.byType(AgentCatalogReportCard), findsNothing);
    expect(find.byType(AgentCatalogDetailCard), findsOneWidget);
  });

  testWidgets('unknown catalog type is informational and cannot navigate',
      (tester) async {
    final repository = _CatalogRepository(
      _turn(catalogItems: const [_unknown]),
    );
    var openCalls = 0;

    await _pumpPage(tester, repository, onOpen: (_) => openCalls++);
    await _sendAndSettle(tester);

    expect(find.text('信息暂不完整'), findsOneWidget);
    expect(find.text('查看详情'), findsNothing);
    expect(openCalls, 0);
  });

  testWidgets('human consultation stays hidden without an explicit user id',
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

    expect(find.text('真人咨询'), findsNothing);
    expect(consultCalls, 0);
  });

  testWidgets('comparison marks every non-navigable item as incomplete',
      (tester) async {
    const report = AgentCatalogReport(
      mode: 'COMPARISON',
      title: '对比',
      summary: '',
      items: [_unknown, _emptyDoctor, _incompleteInstitutionProject],
      comparisonDimensions: ['价格'],
      warnings: [],
    );

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        supportedLocales: const [Locale('zh')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: Scaffold(
          body: AgentComparisonTable(
            report: report,
            onOpen: (_) {},
            canOpen: (_) => false,
          ),
        ),
      ),
    );

    expect(find.text('信息暂不完整'), findsNWidgets(3));
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
  ValueChanged<AgentCatalogItem>? onHumanConsult,
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
  _CatalogRepository(this.turn);

  final ChatTurn turn;

  @override
  Future<List<ChatSession>> getSessions({ChatPersona? persona}) async => [];

  @override
  Future<ChatSession> createSession({
    required ChatPersona persona,
    required ChatContextType contextType,
    String contextId = '',
    String title = '',
  }) async =>
      _session;

  @override
  Future<ChatTurn> sendMessage(String sessionId, String content) async => turn;
}

ChatTurn _turn({
  AgentCatalogReport? report,
  List<AgentCatalogItem> catalogItems = const [],
}) =>
    ChatTurn(
      message: const ChatMessage(
        id: 'assistant-1',
        sessionId: 'session-1',
        role: 'ASSISTANT',
        content: '参考结果',
        createdAt: '2026-08-11T10:01:00',
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

const _duplicateProject = AgentCatalogItem(
  type: 'PROJECT',
  id: 'project-1',
  name: '重复项目',
  subtitle: '',
  summary: '',
  attributes: {},
  institutionId: null,
  projectId: null,
  canChatWithHuman: false,
);

const _doctor = AgentCatalogItem(
  type: 'DOCTOR',
  id: 'doctor-record-1',
  name: '医生一',
  subtitle: '',
  summary: '',
  attributes: {},
  institutionId: null,
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

const _emptyDoctor = AgentCatalogItem(
  type: 'DOCTOR',
  id: '',
  name: '缺少标识的医生',
  subtitle: '',
  summary: '',
  attributes: {},
  institutionId: null,
  projectId: null,
  canChatWithHuman: true,
);

const _incompleteInstitutionProject = AgentCatalogItem(
  type: 'INSTITUTION_PROJECT',
  id: 'institution-project-1',
  name: '缺少关联标识的机构项目',
  subtitle: '',
  summary: '',
  attributes: {},
  institutionId: null,
  projectId: null,
  canChatWithHuman: false,
);

const _report = AgentCatalogReport(
  mode: 'SUMMARY',
  title: '目录报告',
  summary: '',
  items: [_project],
  comparisonDimensions: [],
  warnings: [],
);

class _DiscoverRepository extends Fake implements DiscoverRepository {}
