import 'dart:async';

import 'package:flutter/material.dart';
import 'package:joysong_flutter/core/transient_message.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/presentation/agent_plan_controller.dart';

class AgentProfileSafetyPage extends StatefulWidget {
  const AgentProfileSafetyPage({required this.controller, super.key});
  final AgentPlanController controller;

  @override
  State<AgentProfileSafetyPage> createState() => _AgentProfileSafetyPageState();
}

class _AgentProfileSafetyPageState extends State<AgentProfileSafetyPage> {
  final _city = TextEditingController();
  final _goals = TextEditingController();
  final _budgetMin = TextEditingController();
  final _budgetMax = TextEditingController();
  final _downtime = TextEditingController();
  final _preferences = TextEditingController();
  String _pain = 'MEDIUM';
  bool _pregnant = false;
  bool _skin = false;
  bool _allergy = false;
  bool _medication = false;
  bool _recent = false;
  bool _guarantee = false;
  bool _distress = false;
  bool _profileLoaded = false;
  bool _submitting = false;
  bool _formDirty = false;
  int _profileLoadGeneration = 0;

  bool get _english => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    unawaited(_loadProfile());
  }

  Future<void> _loadProfile() async {
    final generation = ++_profileLoadGeneration;
    final executed = await widget.controller.loadProfile();
    if (!mounted ||
        generation != _profileLoadGeneration ||
        !executed ||
        widget.controller.state.errorMessage != null ||
        (_profileLoaded && _formDirty)) {
      return;
    }
    final profile = widget.controller.state.profile;
    if (profile == null) return;
    _city.text = profile.city;
    _goals.text = profile.goals.join(', ');
    _budgetMin.text = profile.budgetMin ?? '';
    _budgetMax.text = profile.budgetMax ?? '';
    _downtime.text = profile.acceptableDowntimeDays?.toString() ?? '';
    _preferences.text = profile.preferences.join(', ');
    if (profile.painTolerance.isNotEmpty) _pain = profile.painTolerance;
    setState(() => _profileLoaded = true);
  }

  void _retryLoad() {
    setState(() => _profileLoaded = false);
    unawaited(_loadProfile());
  }

  void _markDirty(String _) => _formDirty = true;

  @override
  void dispose() {
    for (final controller in [
      _city,
      _goals,
      _budgetMin,
      _budgetMax,
      _downtime,
      _preferences
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  List<String> _tokens(String value) => value
      .split(RegExp(r'[,，]'))
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toList(growable: false);

  Future<void> _submit() async {
    if (_submitting) return;
    final current = widget.controller.state.profile;
    if (current == null) return;
    setState(() => _submitting = true);
    try {
      final saveExecuted = await widget.controller.saveProfile(
        AgentProfileDraft(
          city: _city.text.trim(),
          goals: _tokens(_goals.text),
          budgetMin:
              _budgetMin.text.trim().isEmpty ? null : _budgetMin.text.trim(),
          budgetMax:
              _budgetMax.text.trim().isEmpty ? null : _budgetMax.text.trim(),
          acceptableDowntimeDays: int.tryParse(_downtime.text),
          painTolerance: _pain,
          preferences: _tokens(_preferences.text),
          excludedProjects: current.excludedProjects,
          consentVersion: current.consentVersion,
        ),
        confirm: true,
      );
      if (!saveExecuted ||
          widget.controller.state.errorMessage != null ||
          !mounted) {
        return;
      }
      final assessmentExecuted =
          await widget.controller.assessAndCreatePlan(AgentSafetyScreening(
        pregnantOrNursing: _pregnant,
        activeSkinCondition: _skin,
        severeAllergyHistory: _allergy,
        takingRelevantMedication: _medication,
        recentProcedure: _recent,
        expectsGuaranteedResult: _guarantee,
        severeDistressAboutAppearance: _distress,
      ));
      if (!assessmentExecuted ||
          !mounted ||
          widget.controller.state.errorMessage != null) {
        return;
      }
      final assessment = widget.controller.state.assessment;
      showTransientMessage(
        context,
        assessment == null
            ? (_english ? 'Unable to complete assessment' : '评估未完成')
            : '${_english ? 'Risk level' : '风险等级'}: ${assessment.riskLevel}',
      );
      if (assessment != null) Navigator.pop(context, true);
    } finally {
      _submitting = false;
      if (mounted) setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: widget.controller,
        builder: (context, _) {
          final state = widget.controller.state;
          return Scaffold(
            appBar:
                AppBar(title: Text(_english ? 'Profile & safety' : '档案与安全筛查')),
            body: !_profileLoaded
                ? _initialBody(state)
                : Column(
                    children: [
                      if (state.errorMessage case final message?)
                        Padding(
                          padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
                          child: _ErrorNotice(message: message),
                        ),
                      Expanded(
                        child: ListView(
                          padding: const EdgeInsets.all(20),
                          children: [
                            Text(_english ? 'Planning profile' : '方案档案',
                                style: Theme.of(context).textTheme.titleLarge),
                            const SizedBox(height: 12),
                            TextField(
                                controller: _city,
                                onChanged: _markDirty,
                                decoration: InputDecoration(
                                    labelText: _english ? 'City' : '所在城市')),
                            TextField(
                                controller: _goals,
                                onChanged: _markDirty,
                                decoration: InputDecoration(
                                    labelText: _english
                                        ? 'Goals (comma separated)'
                                        : '改善目标（逗号分隔）')),
                            Row(children: [
                              Expanded(
                                  child: TextField(
                                      controller: _budgetMin,
                                      onChanged: _markDirty,
                                      keyboardType: TextInputType.number,
                                      decoration: InputDecoration(
                                          labelText: _english
                                              ? 'Minimum budget'
                                              : '最低预算'))),
                              const SizedBox(width: 12),
                              Expanded(
                                  child: TextField(
                                      controller: _budgetMax,
                                      key: const Key('agent-budget-field'),
                                      onChanged: _markDirty,
                                      keyboardType: TextInputType.number,
                                      decoration: InputDecoration(
                                          labelText: _english
                                              ? 'Maximum budget'
                                              : '最高预算'))),
                            ]),
                            TextField(
                                controller: _downtime,
                                onChanged: _markDirty,
                                keyboardType: TextInputType.number,
                                decoration: InputDecoration(
                                    labelText: _english
                                        ? 'Acceptable downtime (days)'
                                        : '可接受恢复期（天）')),
                            DropdownButtonFormField<String>(
                              initialValue: _pain,
                              isExpanded: true,
                              borderRadius: BorderRadius.circular(12),
                              menuMaxHeight: 320,
                              dropdownColor:
                                  Theme.of(context).colorScheme.surface,
                              decoration: InputDecoration(
                                  labelText:
                                      _english ? 'Pain tolerance' : '疼痛耐受'),
                              items: const [
                                DropdownMenuItem(
                                    value: 'LOW', child: Text('Low / 低')),
                                DropdownMenuItem(
                                    value: 'MEDIUM', child: Text('Medium / 中')),
                                DropdownMenuItem(
                                    value: 'HIGH', child: Text('High / 高')),
                              ],
                              onChanged: (value) => setState(() {
                                _formDirty = true;
                                _pain = value ?? _pain;
                              }),
                            ),
                            TextField(
                                controller: _preferences,
                                onChanged: _markDirty,
                                decoration: InputDecoration(
                                    labelText:
                                        _english ? 'Preferences' : '偏好（逗号分隔）')),
                            const SizedBox(height: 24),
                            Text(_english ? 'Safety screening' : '安全筛查',
                                style: Theme.of(context).textTheme.titleLarge),
                            _switch(_english ? 'Pregnant or nursing' : '孕期或哺乳期',
                                _pregnant, (v) => _pregnant = v),
                            _switch(
                                _english
                                    ? 'Active skin condition'
                                    : '存在活动性皮肤问题',
                                _skin,
                                (v) => _skin = v),
                            _switch(
                                _english ? 'Severe allergy history' : '有严重过敏史',
                                _allergy,
                                (v) => _allergy = v),
                            _switch(
                                _english
                                    ? 'Taking relevant medication'
                                    : '正在服用相关药物',
                                _medication,
                                (v) => _medication = v),
                            _switch(_english ? 'Recent procedure' : '近期接受过相关项目',
                                _recent, (v) => _recent = v),
                            _switch(
                                _english
                                    ? 'Expecting guaranteed results'
                                    : '期望保证效果',
                                _guarantee,
                                (v) => _guarantee = v),
                            _switch(
                                _english
                                    ? 'Severe appearance distress'
                                    : '存在严重外貌焦虑',
                                _distress,
                                (v) => _distress = v),
                            const SizedBox(height: 22),
                            FilledButton(
                              onPressed: state.isLoading || _submitting
                                  ? null
                                  : _submit,
                              child: state.isLoading || _submitting
                                  ? const SizedBox.square(
                                      dimension: 20,
                                      child: CircularProgressIndicator(
                                          strokeWidth: 2))
                                  : Text(
                                      _english ? 'Save and assess' : '保存并评估'),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
          );
        },
      );

  Widget _initialBody(AgentPlanState state) {
    final message = state.errorMessage;
    if (message == null || state.isLoading) {
      return const Center(child: CircularProgressIndicator());
    }
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _ErrorNotice(message: message),
            const SizedBox(height: 12),
            FilledButton.tonal(
              key: const Key('agent-profile-retry'),
              onPressed: _retryLoad,
              child: Text(_english ? 'Retry' : '重试'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _switch(String title, bool value, ValueChanged<bool> assign) =>
      SwitchListTile(
        contentPadding: EdgeInsets.zero,
        title: Text(title),
        value: value,
        onChanged: (next) => setState(() {
          _formDirty = true;
          assign(next);
        }),
      );
}

class _ErrorNotice extends StatelessWidget {
  const _ErrorNotice({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => Semantics(
        liveRegion: true,
        child: Text(
          message,
          style: TextStyle(color: Theme.of(context).colorScheme.error),
          textAlign: TextAlign.center,
        ),
      );
}
