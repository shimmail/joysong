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

Future<void> _tapSubmit(WidgetTester tester) async {
  final submit = find.text('保存并评估');
  await tester.scrollUntilVisible(
    submit,
    500,
    scrollable: find.byType(Scrollable).first,
  );
  await tester.tap(submit);
}

class _ProfileRepository extends Fake implements AgentRepository {
  _ProfileRepository({
    Future<AgentProfile>? profileResult,
    List<Future<AgentProfile> Function()>? profileResults,
    this.saveError,
    this.assessmentError,
  })  : _profileResult = profileResult,
        _profileResults = profileResults ?? const [];

  final Future<AgentProfile>? _profileResult;
  final List<Future<AgentProfile> Function()> _profileResults;
  final Object? saveError;
  final Object? assessmentError;
  int getProfileCalls = 0;
  int getPlansCalls = 0;
  int createAssessmentCalls = 0;
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
  Future<AgentProfile> updateProfile(AgentProfileDraft draft) async {
    lastSavedProfile = draft;
    if (saveError case final error?) throw error;
    return _profile;
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

const _assessment = AgentAssessment(
  id: 'assessment-1',
  status: 'BLOCKED',
  completenessScore: 80,
  riskLevel: 'BLOCKED',
  riskReasons: ['需要人工确认'],
  missingFields: [],
  nextAction: 'SAFETY_REVIEW',
);
