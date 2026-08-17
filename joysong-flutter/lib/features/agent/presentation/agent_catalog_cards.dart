import 'package:flutter/material.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';

typedef AgentCatalogItemAction = void Function(AgentCatalogItem item);
typedef AgentCatalogItemPredicate = bool Function(AgentCatalogItem item);
typedef AgentHumanConsultationAction = void Function(String institutionId);

String? _humanConsultationInstitutionId(AgentCatalogItem item) {
  final explicitInstitutionId = item.institutionId?.trim() ?? '';
  final itemId = item.id.trim();
  return switch (item.type.trim().toUpperCase()) {
    'INSTITUTION' =>
      explicitInstitutionId.isNotEmpty
          ? explicitInstitutionId
          : (itemId.isEmpty ? null : itemId),
    'INSTITUTION_PROJECT' =>
      explicitInstitutionId.isEmpty ? null : explicitInstitutionId,
    _ => null,
  };
}

class AgentCatalogLinkList extends StatelessWidget {
  const AgentCatalogLinkList({
    required this.items,
    this.onOpen,
    this.onHumanChat,
    this.canOpen,
    super.key,
  });

  final List<AgentCatalogItem> items;
  final AgentCatalogItemAction? onOpen;
  final AgentHumanConsultationAction? onHumanChat;
  final AgentCatalogItemPredicate? canOpen;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (final item in items) ...[
        AgentCatalogLinkCard(
          item: item,
          onOpen: onOpen,
          onHumanChat: onHumanChat,
          canOpen: canOpen,
        ),
        const SizedBox(height: 8),
      ],
    ],
  );
}

class AgentCatalogLinkCard extends StatelessWidget {
  const AgentCatalogLinkCard({
    required this.item,
    this.onOpen,
    this.onHumanChat,
    this.canOpen,
    super.key,
  });

  final AgentCatalogItem item;
  final AgentCatalogItemAction? onOpen;
  final AgentHumanConsultationAction? onHumanChat;
  final AgentCatalogItemPredicate? canOpen;

  @override
  Widget build(BuildContext context) {
    final open = onOpen != null && (canOpen?.call(item) ?? true);
    final institutionId = item.canChatWithHuman
        ? _humanConsultationInstitutionId(item)
        : null;
    final canConsult = onHumanChat != null && institutionId != null;
    return Card(
      margin: const EdgeInsets.only(top: 6),
      clipBehavior: Clip.antiAlias,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            InkWell(
              onTap: open ? () => onOpen!(item) : null,
              child: Row(
                children: [
                  CircleAvatar(child: Icon(_typeIcon(item.type))),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          item.name,
                          style: Theme.of(context).textTheme.titleSmall,
                        ),
                        if (item.subtitle.trim().isNotEmpty)
                          Text(
                            item.subtitle,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                      ],
                    ),
                  ),
                  if (open) const Icon(Icons.chevron_right),
                ],
              ),
            ),
            if (canConsult)
              Align(
                alignment: Alignment.centerRight,
                child: TextButton.icon(
                  key: ValueKey('agent-human-consult-${item.type}-${item.id}'),
                  onPressed: () => onHumanChat!(institutionId),
                  icon: const Icon(Icons.chat_bubble_outline, size: 18),
                  label: Text(
                    Localizations.localeOf(context).languageCode == 'en'
                        ? 'Ask a specialist'
                        : '真人咨询',
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class AgentCatalogReferenceList extends StatelessWidget {
  const AgentCatalogReferenceList({
    required this.items,
    this.onOpen,
    this.canOpen,
    super.key,
  });

  final List<AgentCatalogItem> items;
  final AgentCatalogItemAction? onOpen;
  final AgentCatalogItemPredicate? canOpen;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (final item in items) ...[
        AgentCatalogDetailCard(item: item, onOpen: onOpen, canOpen: canOpen),
        const SizedBox(height: 8),
      ],
    ],
  );
}

class AgentCatalogDetailCard extends StatelessWidget {
  const AgentCatalogDetailCard({
    required this.item,
    this.onOpen,
    this.onHumanChat,
    this.canOpen,
    this.compact = false,
    super.key,
  });

  final AgentCatalogItem item;
  final AgentCatalogItemAction? onOpen;
  final AgentHumanConsultationAction? onHumanChat;
  final AgentCatalogItemPredicate? canOpen;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final supported = canOpen?.call(item) ?? true;
    final open = onOpen != null && supported;
    final institutionId = item.canChatWithHuman
        ? _humanConsultationInstitutionId(item)
        : null;
    final canConsult = onHumanChat != null && institutionId != null;
    return Card(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: open ? () => onOpen!(item) : null,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  CircleAvatar(child: Icon(_typeIcon(item.type))),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          item.name,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        if (item.subtitle.isNotEmpty)
                          Text(
                            item.subtitle,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                      ],
                    ),
                  ),
                  _TypeChip(type: item.type),
                ],
              ),
              if (item.attributes.isNotEmpty) ...[
                const SizedBox(height: 12),
                for (final entry in item.attributes.entries.take(
                  compact ? 2 : 8,
                ))
                  Padding(
                    padding: const EdgeInsets.only(bottom: 6),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: 86,
                          child: Text(
                            entry.key,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ),
                        Expanded(child: Text(entry.value)),
                      ],
                    ),
                  ),
              ],
              if (!compact && item.summary.isNotEmpty) ...[
                const Divider(height: 20),
                Text(item.summary),
              ],
              if (open || canConsult)
                Row(
                  children: [
                    if (open)
                      TextButton(
                        onPressed: () => onOpen!(item),
                        child: Text(english ? 'View details' : '查看详情'),
                      ),
                    if (canConsult)
                      TextButton.icon(
                        key: ValueKey(
                          'agent-human-consult-${item.type}-${item.id}',
                        ),
                        onPressed: () => onHumanChat!(institutionId),
                        icon: const Icon(Icons.chat_bubble_outline, size: 18),
                        label: Text(english ? 'Ask a specialist' : '真人咨询'),
                      ),
                  ],
                ),
              if (!supported) ...[
                const SizedBox(height: 8),
                Text(
                  english ? 'Information is incomplete' : '信息暂不完整',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class AgentCatalogReportCard extends StatelessWidget {
  const AgentCatalogReportCard({
    required this.report,
    this.onOpen,
    this.onHumanChat,
    this.canOpen,
    super.key,
  });

  final AgentCatalogReport report;
  final AgentCatalogItemAction? onOpen;
  final AgentHumanConsultationAction? onHumanChat;
  final AgentCatalogItemPredicate? canOpen;

  bool get _isComparison => report.mode.trim().toUpperCase() == 'COMPARISON';

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    return Card(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      color: Theme.of(context).colorScheme.surfaceContainerLow,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  _isComparison
                      ? Icons.compare_arrows_rounded
                      : Icons.auto_awesome_outlined,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    report.title.isEmpty
                        ? (english ? 'AI report' : 'AI 分析报告')
                        : report.title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                Chip(
                  visualDensity: VisualDensity.compact,
                  label: Text(
                    _isComparison
                        ? (english ? 'Comparison' : '对比')
                        : (english ? 'Summary' : '汇总'),
                  ),
                ),
              ],
            ),
            if (report.summary.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(report.summary),
            ],
            const SizedBox(height: 14),
            if (_isComparison)
              AgentComparisonTable(
                report: report,
                onOpen: onOpen,
                canOpen: canOpen,
              )
            else
              AgentCatalogReferenceList(
                items: report.items,
                onOpen: onOpen,
                canOpen: canOpen,
              ),
            ..._humanConsultationActions(english),
            if (report.warnings.isNotEmpty) ...[
              const Divider(height: 24),
              for (final warning in report.warnings)
                Padding(
                  padding: const EdgeInsets.only(bottom: 5),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(
                        Icons.warning_amber_rounded,
                        size: 18,
                        color: Theme.of(context).colorScheme.error,
                      ),
                      const SizedBox(width: 6),
                      Expanded(child: Text(warning)),
                    ],
                  ),
                ),
            ],
          ],
        ),
      ),
    );
  }

  List<Widget> _humanConsultationActions(bool english) {
    final actions = <Widget>[];
    for (final item in report.items) {
      final institutionId = item.canChatWithHuman
          ? _humanConsultationInstitutionId(item)
          : null;
      final canConsult = onHumanChat != null && institutionId != null;
      if (!canConsult) continue;
      actions.add(
        Align(
          alignment: Alignment.centerRight,
          child: TextButton.icon(
            key: ValueKey('agent-human-consult-${item.type}-${item.id}'),
            onPressed: () => onHumanChat!(institutionId),
            icon: const Icon(Icons.support_agent, size: 18),
            label: Text('${english ? 'Consult' : '咨询'} ${item.name}'),
          ),
        ),
      );
    }
    return actions;
  }
}

class AgentComparisonStatusCard extends StatelessWidget {
  const AgentComparisonStatusCard({required this.request, super.key});

  final AgentComparisonRequest request;

  @override
  Widget build(BuildContext context) {
    final isZh = Localizations.localeOf(context).languageCode == 'zh';
    final operandNames = request.operands
        .map((item) => item.displayName.trim())
        .where((name) => name.isNotEmpty)
        .toList(growable: false);
    final guidance = request.missingFields
        .map(
          (field) => switch (field) {
            'OPERANDS' =>
              isZh ? '请选择至少两个对比对象' : 'Select at least two items to compare',
            'TARGET_TYPE' =>
              isZh
                  ? '请明确要比较机构、医生、项目还是机构项目'
                  : 'Specify whether to compare clinics, doctors, treatments, or clinic treatments',
            _ => null,
          },
        )
        .whereType<String>();
    return Card(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              isZh ? '还需要补充对比信息' : 'More comparison details needed',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (operandNames.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(operandNames.join(' · ')),
            ],
            for (final text in guidance) ...[
              const SizedBox(height: 8),
              Text(text),
            ],
          ],
        ),
      ),
    );
  }
}

class AgentComparisonTable extends StatelessWidget {
  const AgentComparisonTable({
    required this.report,
    this.onOpen,
    this.canOpen,
    super.key,
  });

  final AgentCatalogReport report;
  final AgentCatalogItemAction? onOpen;
  final AgentCatalogItemPredicate? canOpen;

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final visibleItems = report.items.take(4).toList(growable: false);
    final dimensions = report.comparisonDimensions.isNotEmpty
        ? report.comparisonDimensions
        : _allAttributeKeys(visibleItems);
    final missingValue = english ? 'Not available' : '暂无平台数据';
    return Scrollbar(
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: DataTable(
          headingRowHeight: 64,
          dataRowMinHeight: 52,
          dataRowMaxHeight: double.infinity,
          columns: [
            const DataColumn(label: Text('')),
            for (final item in visibleItems)
              DataColumn(
                label: SizedBox(
                  width: 124,
                  child: InkWell(
                    onTap: onOpen != null && (canOpen?.call(item) ?? true)
                        ? () => onOpen!(item)
                        : null,
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          item.name,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        if (!(canOpen?.call(item) ?? true))
                          Text(
                            english ? 'Information is incomplete' : '信息暂不完整',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                      ],
                    ),
                  ),
                ),
              ),
          ],
          rows: [
            for (final dimension in dimensions)
              DataRow(
                cells: [
                  DataCell(
                    SizedBox(
                      width: 82,
                      child: Text(
                        dimension,
                        style: Theme.of(context).textTheme.labelMedium,
                      ),
                    ),
                  ),
                  for (final item in visibleItems)
                    DataCell(
                      SizedBox(
                        width: 124,
                        child: Text(
                          item.attributes[dimension]?.trim().isNotEmpty == true
                              ? item.attributes[dimension]!
                              : missingValue,
                        ),
                      ),
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}

class _TypeChip extends StatelessWidget {
  const _TypeChip({required this.type});
  final String type;

  @override
  Widget build(BuildContext context) {
    final english = Localizations.localeOf(context).languageCode == 'en';
    final label = switch (type.toUpperCase()) {
      'DOCTOR' => english ? 'Doctor' : '医生',
      'INSTITUTION' => english ? 'Institution' : '机构',
      'PROJECT' || 'INSTITUTION_PROJECT' => english ? 'Project' : '项目',
      _ => english ? 'Reference' : '参考',
    };
    return Chip(visualDensity: VisualDensity.compact, label: Text(label));
  }
}

IconData _typeIcon(String type) => switch (type.toUpperCase()) {
  'DOCTOR' => Icons.medical_services_outlined,
  'INSTITUTION' => Icons.apartment_outlined,
  'PROJECT' || 'INSTITUTION_PROJECT' => Icons.auto_awesome_outlined,
  _ => Icons.info_outline,
};

List<String> _allAttributeKeys(List<AgentCatalogItem> items) {
  final result = <String>[];
  final seen = <String>{};
  for (final item in items) {
    for (final key in item.attributes.keys) {
      if (seen.add(key)) result.add(key);
    }
  }
  return result;
}
