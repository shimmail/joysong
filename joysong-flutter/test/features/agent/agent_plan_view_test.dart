import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_view.dart';

void main() {
  testWidgets(
      'plan always exposes limitations risks alternatives and confirmations',
      (tester) async {
    const item = AgentPlanItem(
      id: 'item-1',
      stage: 'FIRST',
      projectId: 'project-1',
      projectName: '示例项目',
      recommendationType: 'OPTION',
      reason: '匹配目标',
      expectedBenefit: '可能改善肤质',
      limitations: '效果存在个体差异',
      risks: ['红肿'],
      alternatives: ['日常护理'],
      requiredConfirmations: ['面诊确认禁忌症'],
      confidence: 'MEDIUM',
    );
    const plan = AgentPlan(
      id: 'plan-1',
      assessmentId: 'assessment-1',
      version: 1,
      status: 'READY',
      summary: '方案摘要',
      totalBudgetMin: '1000.00',
      totalBudgetMax: '3000.00',
      items: [item],
      createdAt: '2026-08-06T10:00:00',
    );

    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: AgentPlanView(plan: plan))),
    );

    expect(find.text('限制'), findsOneWidget);
    expect(find.text('风险'), findsOneWidget);
    expect(find.text('替代方案'), findsOneWidget);
    expect(find.text('待确认项'), findsOneWidget);
    expect(find.textContaining('不构成医疗诊断'), findsOneWidget);
  });
}
