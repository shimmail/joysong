import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/core/network/api_exception.dart';
import 'package:joysong_flutter/features/auth/domain/auth_models.dart';
import 'package:joysong_flutter/features/profile/domain/profile_models.dart';
import 'package:joysong_flutter/features/profile/domain/profile_repository.dart';

enum ProfileLoadStatus { idle, loading, ready, failure }

final class ProfileController extends ChangeNotifier {
  ProfileController(this._repository);

  final ProfileRepository _repository;

  ProfileLoadStatus _status = ProfileLoadStatus.idle;
  AuthUser? _user;
  String? _errorMessage;
  bool _isSaving = false;

  ProfileLoadStatus get status => _status;
  AuthUser? get user => _user;
  String? get errorMessage => _errorMessage;
  bool get isSaving => _isSaving;

  Future<void> load({bool refresh = false}) async {
    if (_status == ProfileLoadStatus.loading || _isSaving) return;
    if (!refresh && _status == ProfileLoadStatus.ready) return;
    _status = ProfileLoadStatus.loading;
    _errorMessage = null;
    notifyListeners();
    try {
      _user = await _repository.getProfile();
      _status = ProfileLoadStatus.ready;
    } catch (error) {
      _status = ProfileLoadStatus.failure;
      _errorMessage = _messageFor(error, '个人资料加载失败');
    }
    notifyListeners();
  }

  Future<bool> save(ProfileUpdate update) async {
    if (_isSaving) return false;
    try {
      update.validate();
    } on ArgumentError catch (error) {
      _errorMessage = error.message?.toString() ?? '请检查个人资料';
      notifyListeners();
      return false;
    }
    _isSaving = true;
    _errorMessage = null;
    notifyListeners();
    try {
      _user = await _repository.updateProfile(update);
      _status = ProfileLoadStatus.ready;
      return true;
    } catch (error) {
      _errorMessage = _messageFor(error, '个人资料保存失败');
      return false;
    } finally {
      _isSaving = false;
      notifyListeners();
    }
  }
}

String _messageFor(Object error, String fallback) => switch (error) {
      ApiException(:final message) when message.isNotEmpty => message,
      FormatException(:final message) when message.isNotEmpty => message,
      ArgumentError(:final message) when message != null => message.toString(),
      _ => fallback,
    };
