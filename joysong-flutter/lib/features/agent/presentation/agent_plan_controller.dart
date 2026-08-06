import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/agent/domain/agent_models.dart';
import 'package:joysong_flutter/features/agent/domain/agent_repository.dart';

class AgentPlanState {
  const AgentPlanState({
    this.profile,
    this.assessment,
    this.selectedPlan,
    this.plans = const [],
    this.isLoading = false,
    this.errorMessage,
  });

  final AgentProfile? profile;
  final AgentAssessment? assessment;
  final AgentPlan? selectedPlan;
  final List<AgentPlan> plans;
  final bool isLoading;
  final String? errorMessage;

  AgentPlanState copyWith({
    AgentProfile? profile,
    AgentAssessment? assessment,
    AgentPlan? selectedPlan,
    List<AgentPlan>? plans,
    bool? isLoading,
    String? errorMessage,
    bool clearError = false,
  }) =>
      AgentPlanState(
        profile: profile ?? this.profile,
        assessment: assessment ?? this.assessment,
        selectedPlan: selectedPlan ?? this.selectedPlan,
        plans: plans ?? this.plans,
        isLoading: isLoading ?? this.isLoading,
        errorMessage: clearError ? null : errorMessage ?? this.errorMessage,
      );
}

class AgentPlanController extends ChangeNotifier {
  AgentPlanController(this._repository);

  final AgentRepository _repository;
  AgentPlanState _state = const AgentPlanState();
  AgentPlanState get state => _state;
  bool _disposed = false;

  Future<void> load() => _run(() async {
        final results = await Future.wait<Object>([
          _repository.getProfile(),
          _repository.getPlans(),
        ]);
        _emit(
          _state.copyWith(
            profile: results[0] as AgentProfile,
            plans: results[1] as List<AgentPlan>,
          ),
        );
      });

  Future<void> saveProfile(AgentProfileDraft draft, {bool confirm = false}) =>
      _run(() async {
        var profile = await _repository.updateProfile(draft);
        if (confirm) {
          profile = await _repository.confirmProfile();
        }
        _emit(_state.copyWith(profile: profile));
      });

  Future<void> assessAndCreatePlan(AgentSafetyScreening screening) =>
      _run(() async {
        final assessment = await _repository.createAssessment(screening);
        _emit(_state.copyWith(assessment: assessment));
        if (assessment.riskLevel.toUpperCase() == 'BLOCKED' ||
            assessment.nextAction.toUpperCase().contains('SAFETY')) {
          return;
        }
        final plan = await _repository.createPlan(assessment.id);
        _emit(
          _state.copyWith(
            selectedPlan: plan,
            plans: [plan, ..._state.plans.where((item) => item.id != plan.id)],
          ),
        );
      });

  void selectPlan(AgentPlan plan) =>
      _emit(_state.copyWith(selectedPlan: plan, clearError: true));

  Future<void> deletePlan(String planId) => _run(() async {
        await _repository.deletePlan(planId);
        final remaining =
            _state.plans.where((plan) => plan.id != planId).toList();
        _state = AgentPlanState(
          profile: _state.profile,
          assessment: _state.assessment,
          selectedPlan:
              _state.selectedPlan?.id == planId ? null : _state.selectedPlan,
          plans: remaining,
          isLoading: _state.isLoading,
          errorMessage: _state.errorMessage,
        );
        notifyListeners();
      });

  Future<void> _run(Future<void> Function() action) async {
    if (_state.isLoading) return;
    _emit(_state.copyWith(isLoading: true, clearError: true));
    try {
      await action();
      _emit(_state.copyWith(isLoading: false));
    } on Object catch (error) {
      _emit(
        _state.copyWith(
          isLoading: false,
          errorMessage: error is ApiException ? error.message : '操作失败，请稍后重试',
        ),
      );
    }
  }

  void _emit(AgentPlanState state) {
    if (_disposed) return;
    _state = state;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
