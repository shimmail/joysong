import 'package:flutter/material.dart';
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

  bool get _english => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    final profile = widget.controller.state.profile;
    if (profile != null) {
      _city.text = profile.city;
      _goals.text = profile.goals.join(', ');
      _budgetMin.text = profile.budgetMin ?? '';
      _budgetMax.text = profile.budgetMax ?? '';
      _downtime.text = profile.acceptableDowntimeDays?.toString() ?? '';
      _preferences.text = profile.preferences.join(', ');
      if (profile.painTolerance.isNotEmpty) _pain = profile.painTolerance;
    }
  }

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
    await widget.controller.saveProfile(
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
      ),
      confirm: true,
    );
    if (widget.controller.state.errorMessage != null || !mounted) return;
    await widget.controller.assessAndCreatePlan(AgentSafetyScreening(
      pregnantOrNursing: _pregnant,
      activeSkinCondition: _skin,
      severeAllergyHistory: _allergy,
      takingRelevantMedication: _medication,
      recentProcedure: _recent,
      expectsGuaranteedResult: _guarantee,
      severeDistressAboutAppearance: _distress,
    ));
    if (!mounted) return;
    final assessment = widget.controller.state.assessment;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(
      assessment == null
          ? (_english ? 'Unable to complete assessment' : '评估未完成')
          : '${_english ? 'Risk level' : '风险等级'}: ${assessment.riskLevel}',
    )));
    if (assessment != null) Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: widget.controller,
        builder: (context, _) => Scaffold(
          appBar:
              AppBar(title: Text(_english ? 'Profile & safety' : '档案与安全筛查')),
          body: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              Text(_english ? 'Planning profile' : '方案档案',
                  style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              TextField(
                  controller: _city,
                  decoration:
                      InputDecoration(labelText: _english ? 'City' : '所在城市')),
              TextField(
                  controller: _goals,
                  decoration: InputDecoration(
                      labelText:
                          _english ? 'Goals (comma separated)' : '改善目标（逗号分隔）')),
              Row(children: [
                Expanded(
                    child: TextField(
                        controller: _budgetMin,
                        keyboardType: TextInputType.number,
                        decoration: InputDecoration(
                            labelText: _english ? 'Minimum budget' : '最低预算'))),
                const SizedBox(width: 12),
                Expanded(
                    child: TextField(
                        controller: _budgetMax,
                        keyboardType: TextInputType.number,
                        decoration: InputDecoration(
                            labelText: _english ? 'Maximum budget' : '最高预算'))),
              ]),
              TextField(
                  controller: _downtime,
                  keyboardType: TextInputType.number,
                  decoration: InputDecoration(
                      labelText: _english
                          ? 'Acceptable downtime (days)'
                          : '可接受恢复期（天）')),
              DropdownButtonFormField<String>(
                initialValue: _pain,
                decoration: InputDecoration(
                    labelText: _english ? 'Pain tolerance' : '疼痛耐受'),
                items: const [
                  DropdownMenuItem(value: 'LOW', child: Text('Low / 低')),
                  DropdownMenuItem(value: 'MEDIUM', child: Text('Medium / 中')),
                  DropdownMenuItem(value: 'HIGH', child: Text('High / 高')),
                ],
                onChanged: (value) => setState(() => _pain = value ?? _pain),
              ),
              TextField(
                  controller: _preferences,
                  decoration: InputDecoration(
                      labelText: _english ? 'Preferences' : '偏好（逗号分隔）')),
              const SizedBox(height: 24),
              Text(_english ? 'Safety screening' : '安全筛查',
                  style: Theme.of(context).textTheme.titleLarge),
              _switch(_english ? 'Pregnant or nursing' : '孕期或哺乳期', _pregnant,
                  (v) => _pregnant = v),
              _switch(_english ? 'Active skin condition' : '存在活动性皮肤问题', _skin,
                  (v) => _skin = v),
              _switch(_english ? 'Severe allergy history' : '有严重过敏史', _allergy,
                  (v) => _allergy = v),
              _switch(_english ? 'Taking relevant medication' : '正在服用相关药物',
                  _medication, (v) => _medication = v),
              _switch(_english ? 'Recent procedure' : '近期接受过相关项目', _recent,
                  (v) => _recent = v),
              _switch(_english ? 'Expecting guaranteed results' : '期望保证效果',
                  _guarantee, (v) => _guarantee = v),
              _switch(_english ? 'Severe appearance distress' : '存在严重外貌焦虑',
                  _distress, (v) => _distress = v),
              const SizedBox(height: 22),
              FilledButton(
                onPressed: widget.controller.state.isLoading ? null : _submit,
                child: widget.controller.state.isLoading
                    ? const SizedBox.square(
                        dimension: 20,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(_english ? 'Assess and create plan' : '完成评估并生成方案'),
              ),
            ],
          ),
        ),
      );

  Widget _switch(String title, bool value, ValueChanged<bool> assign) =>
      SwitchListTile(
        contentPadding: EdgeInsets.zero,
        title: Text(title),
        value: value,
        onChanged: (next) => setState(() => assign(next)),
      );
}
