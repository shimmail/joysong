import 'package:flutter/material.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';

class AgentPlanView extends StatelessWidget {
  const AgentPlanView({required this.plan, super.key});

  final AgentPlan plan;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('辅助方案 · V${plan.version}',
            style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        Text(plan.summary),
        const SizedBox(height: 8),
        const _Disclaimer(),
        const SizedBox(height: 16),
        for (final item in plan.items) ...[
          AgentPlanItemCard(item: item),
          const SizedBox(height: 12),
        ],
      ],
    );
  }
}

class AgentPlanItemCard extends StatelessWidget {
  const AgentPlanItemCard({required this.item, super.key});

  final AgentPlanItem item;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    item.projectName.isEmpty ? '待确认方案' : item.projectName,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                Text('置信度 ${_confidence(item.confidence)}'),
              ],
            ),
            const SizedBox(height: 12),
            _TextSection(title: '建议理由', body: item.reason),
            _TextSection(title: '预期收益', body: item.expectedBenefit),
            _TextSection(title: '限制', body: item.limitations),
            _BulletSection(title: '风险', items: item.risks),
            _BulletSection(title: '替代方案', items: item.alternatives),
            _BulletSection(title: '待确认项', items: item.requiredConfirmations),
          ],
        ),
      ),
    );
  }
}

class _TextSection extends StatelessWidget {
  const _TextSection({required this.title, required this.body});
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    if (body.trim().isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 3),
          Text(body),
        ],
      ),
    );
  }
}

class _BulletSection extends StatelessWidget {
  const _BulletSection({required this.title, required this.items});
  final String title;
  final List<String> items;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: 3),
          for (final item in items) Text('• $item'),
        ],
      ),
    );
  }
}

class _Disclaimer extends StatelessWidget {
  const _Disclaimer();

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(12),
        ),
        child: const Text(
          'AI 方案仅用于辅助决策，不构成医疗诊断或效果承诺；请由具备资质的专业人员面诊确认。',
        ),
      );
}

String _confidence(String value) => switch (value.toUpperCase()) {
      'HIGH' => '高',
      'MEDIUM' => '中',
      _ => '低',
    };
