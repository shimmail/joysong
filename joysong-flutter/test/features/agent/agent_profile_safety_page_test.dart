import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_controller.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_profile_safety_page.dart';

void main() {
  testWidgets('loads the server profile before constructing the edit form',
      (tester) async {
    final pendingProfile = Completer<AgentProfile>();
    final repository = _ProfileRepository(profileResult: pendingProfile.future);
    final controller = AgentPlanController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_app(controller));
    await tester.pump();

    expect(repository.getProfileCalls, 1);
    expect(repository.getPlansCalls, 0);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.byKey(const Key('agent-budget-field')), findsNothing);

    pendingProfile.complete(_profile);
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('agent-budget-field')), findsOneWidget);
    expect(find.text('上海'), findsOneWidget);
  });

  testWidgets('ignores a profile response after the page is disposed',
      (tester) async {
    final pendingProfile = Completer<AgentProfile>();
    final repository = _ProfileRepository(profileResult: pendingProfile.future);
    final controller = AgentPlanController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_app(controller));
    await tester.pump();
    expect(repository.getProfileCalls, 1);

    await tester.pumpWidget(const SizedBox.shrink());
    pendingProfile.complete(_profile);
    await tester.pump();

    expect(tester.takeException(), isNull);
  });

  testWidgets('shows the profile load error and retries on demand',
      (tester) async {
    final repository = _ProfileRepository(
      profileResults: [
        () => Future<AgentProfile>.error(
              const ApiException(message: '档案加载失败，请检查网络'),
            ),
        () => Future.value(_profile),
      ],
    );
    final controller = AgentPlanController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_app(controller));
    await tester.pumpAndSettle();

    expect(find.text('档案加载失败，请检查网络'), findsOneWidget);
    expect(find.text('重试'), findsOneWidget);
    expect(find.byKey(const Key('agent-budget-field')), findsNothing);

    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();

    expect(repository.getProfileCalls, 2);
    expect(find.byKey(const Key('agent-budget-field')), findsOneWidget);
  });

  testWidgets('preserves fields that the form does not edit when saving',
      (tester) async {
    final repository =
        _ProfileRepository(profileResult: Future.value(_profile));
    final controller = AgentPlanController(repository);
    addTearDown(controller.dispose);

    await tester.pumpWidget(_app(controller));
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const Key('agent-budget-field')),
      '12000',
    );
    await _tapSubmit(tester);
    await tester.pumpAndSettle();

    expect(repository.lastSavedProfile?.budgetMax, '12000');
    expect(repository.lastSavedProfile?.excludedProjects, ['project-1']);
    expect(repository.lastSavedProfile?.consentVersion, 'agent-profile-v2');
  });

  testWidgets('keeps a concrete save error visible', (tester) async {
    final saveRepository = _ProfileRepository(
      profileResult: Future.value(_profile),
      saveError: const ApiException(message: '保存失败：档案版本冲突'),
    );
    final saveController = AgentPlanController(saveRepository);
    addTearDown(saveController.dispose);

    await tester.pumpWidget(_app(saveController));
    await tester.pumpAndSettle();
    await _tapSubmit(tester);
    await tester.pumpAndSettle();

    expect(find.text('保存失败：档案版本冲突'), findsOneWidget);
    expect(saveRepository.createAssessmentCalls, 0);
  });

  testWidgets('keeps a concrete assessment error visible', (tester) async {
    final assessmentRepository = _ProfileRepository(
      profileResult: Future.value(_profile),
      assessmentError: const ApiException(message: '评估失败：信息不完整'),
    );
    final assessmentController = AgentPlanController(assessmentRepository);
    addTearDown(assessmentController.dispose);

    await tester.pumpWidget(_app(assessmentController));
    await tester.pumpAndSettle();
    await _tapSubmit(tester);
    await tester.pumpAndSettle();

    expect(assessmentRepository.createAssessmentCalls, 1);
    expect(find.text('评估失败：信息不完整'), findsOneWidget);
  });

  testWidgets(
      'double submit cannot complete from a stale assessment while save is pending',
      (tester) async {
    final pendingSave = Completer<AgentProfile>();
    final repository = _ProfileRepository(
      profileResult: Future.value(_profile),
      saveResult: pendingSave.future,
    );
    final controller = AgentPlanController(repository);
    addTearDown(controller.dispose);
    await controller.assessAndCreatePlan(const AgentSafetyScreening());
    expect(controller.state.assessment?.id, 'assessment-1');

    final launcherKey = GlobalKey<_ProfileLauncherState>();
    await tester.pumpWidget(_routeApp(controller, launcherKey));
    await tester.tap(find.byKey(const Key('open-agent-profile')));
    await tester.pumpAndSettle();
    final submit = await _findSubmit(tester);
    final button = tester.widget<FilledButton>(submit);

    button.onPressed!();
    button.onPressed!();
    await tester.pump();

    expect(repository.updateProfileCalls, 1);
    expect(repository.createAssessmentCalls, 1);
    expect(launcherKey.currentState?.result, isNull);
    expect(find.byType(AgentProfileSafetyPage), findsOneWidget);

    pendingSave.complete(_profile);
    await tester.pumpAndSettle();
    expect(repository.createAssessmentCalls, 2);
    expect(launcherKey.currentState?.result, isTrue);
  });

  testWidgets('profile loading still executes while a mutation is pending',
      (tester) async {
    final pendingSave = Completer<AgentProfile>();
    final pendingProfile = Completer<AgentProfile>();
    final repository = _ProfileRepository(
      profileResult: pendingProfile.future,
      saveResult: pendingSave.future,
    );
    final controller = AgentPlanController(repository);
    addTearDown(controller.dispose);
    addTearDown(() {
      if (!pendingSave.isCompleted) pendingSave.complete(_staleProfile);
      if (!pendingProfile.isCompleted) pendingProfile.complete(_profile);
    });

    final save = controller.saveProfile(const AgentProfileDraft());
    await tester.pumpWidget(_app(controller));
    await tester.pump();

    expect(repository.getProfileCalls, 0);
    expect(find.byKey(const Key('agent-budget-field')), findsNothing);

    pendingSave.complete(_staleProfile);
    await save;
    await tester.pump();

    expect(repository.getProfileCalls, 1);
    expect(find.byKey(const Key('agent-budget-field')), findsNothing);

    pendingProfile.complete(_profile);
    await tester.pumpAndSettle();

    final field = tester.widget<TextField>(
      find.byKey(const Key('agent-budget-field')),
    );
    expect(field.controller?.text, '8000');
  });

  testWidgets('concurrent retry does not expose a stale profile',
      (tester) async {
    final pendingProfile = Completer<AgentProfile>();
    final repository = _ProfileRepository(
      profileResults: [
        () => Future.value(_staleProfile),
        () => Future<AgentProfile>.error(
              const ApiException(message: '档案加载失败，请检查网络'),
            ),
        () => pendingProfile.future,
      ],
    );
    final controller = AgentPlanController(repository);
    addTearDown(controller.dispose);
    addTearDown(() {
      if (!pendingProfile.isCompleted) pendingProfile.complete(_profile);
    });
    await controller.loadProfile();

    await tester.pumpWidget(_app(controller));
    await tester.pumpAndSettle();
    final retry = tester.widget<FilledButton>(
      find.byKey(const Key('agent-profile-retry')),
    );

    retry.onPressed!();
    retry.onPressed!();
    await tester.pump();

    expect(repository.getProfileCalls, 3);
    expect(find.byKey(const Key('agent-budget-field')), findsNothing);

    pendingProfile.complete(_profile);
    await tester.pumpAndSettle();

    final field = tester.widget<TextField>(
      find.byKey(const Key('agent-budget-field')),
    );
    expect(field.controller?.text, '8000');
  });
}

Widget _app(AgentPlanController controller) => MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: AgentProfileSafetyPage(controller: controller),
    );

Widget _routeApp(
  AgentPlanController controller,
  GlobalKey<_ProfileLauncherState> launcherKey,
) =>
    MaterialApp(
      locale: const Locale('zh'),
      supportedLocales: const [Locale('zh')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: _ProfileLauncher(key: launcherKey, controller: controller),
    );

class _ProfileLauncher extends StatefulWidget {
  const _ProfileLauncher({required this.controller, super.key});

  final AgentPlanController controller;

  @override
  State<_ProfileLauncher> createState() => _ProfileLauncherState();
}

class _ProfileLauncherState extends State<_ProfileLauncher> {
  bool? result;

  @override
  Widget build(BuildContext context) => Scaffold(
        body: FilledButton(
          key: const Key('open-agent-profile'),
          onPressed: () async {
            result = await Navigator.of(context).push<bool>(
              MaterialPageRoute(
                builder: (_) => AgentProfileSafetyPage(
                  controller: widget.controller,
                ),
              ),
            );
            if (mounted) setState(() {});
          },
          child: const Text('打开档案'),
        ),
      );
}

Future<void> _tapSubmit(WidgetTester tester) async {
  final submit = await _findSubmit(tester);
  await tester.tap(submit);
}

Future<Finder> _findSubmit(WidgetTester tester) async {
  final label = find.text('保存并评估');
  await tester.scrollUntilVisible(
    label,
    500,
    scrollable: find.byType(Scrollable).first,
  );
  return find.ancestor(of: label, matching: find.byType(FilledButton));
}

class _ProfileRepository extends Fake implements AgentRepository {
  _ProfileRepository({
    Future<AgentProfile>? profileResult,
    List<Future<AgentProfile> Function()>? profileResults,
    this.saveError,
    this.saveResult,
    this.assessmentError,
  })  : _profileResult = profileResult,
        _profileResults = profileResults ?? const [];

  final Future<AgentProfile>? _profileResult;
  final List<Future<AgentProfile> Function()> _profileResults;
  final Object? saveError;
  final Future<AgentProfile>? saveResult;
  final Object? assessmentError;
  int getProfileCalls = 0;
  int getPlansCalls = 0;
  int createAssessmentCalls = 0;
  int updateProfileCalls = 0;
  AgentProfileDraft? lastSavedProfile;

  @override
  Future<AgentProfile> getProfile() {
    final index = getProfileCalls++;
    return index < _profileResults.length
        ? _profileResults[index]()
        : _profileResult ?? Future.value(_profile);
  }

  @override
  Future<List<AgentPlan>> getPlans() async {
    getPlansCalls++;
    return const [];
  }

  @override
  Future<AgentProfile> updateProfile(AgentProfileDraft draft) {
    updateProfileCalls++;
    lastSavedProfile = draft;
    if (saveError case final error?) return Future.error(error);
    return saveResult ?? Future.value(_profile);
  }

  @override
  Future<AgentProfile> confirmProfile() async => _profile;

  @override
  Future<AgentAssessment> createAssessment(
    AgentSafetyScreening screening,
  ) async {
    createAssessmentCalls++;
    if (assessmentError case final error?) throw error;
    return _assessment;
  }
}

const _profile = AgentProfile(
  id: 'profile-1',
  city: '上海',
  goals: ['改善肤质'],
  budgetMin: '1000',
  budgetMax: '8000',
  acceptableDowntimeDays: 3,
  painTolerance: 'MEDIUM',
  preferences: ['自然'],
  excludedProjects: ['project-1'],
  consentVersion: 'agent-profile-v2',
  confirmedAt: null,
  completenessScore: 80,
  missingFields: [],
);

const _staleProfile = AgentProfile(
  id: 'profile-stale',
  city: '北京',
  goals: ['旧目标'],
  budgetMin: '500',
  budgetMax: '5000',
  acceptableDowntimeDays: 1,
  painTolerance: 'LOW',
  preferences: ['旧偏好'],
  excludedProjects: ['project-old'],
  consentVersion: 'agent-profile-v1',
  confirmedAt: null,
  completenessScore: 50,
  missingFields: [],
);

const _assessment = AgentAssessment(
  id: 'assessment-1',
  status: 'BLOCKED',
  completenessScore: 80,
  riskLevel: 'BLOCKED',
  riskReasons: ['需要人工确认'],
  missingFields: [],
  nextAction: 'SAFETY_REVIEW',
);
