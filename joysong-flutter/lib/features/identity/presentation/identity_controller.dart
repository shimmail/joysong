import 'package:flutter/foundation.dart';
import 'package:joysong_flutter/features/identity/domain/identity_models.dart';
import 'package:joysong_flutter/features/identity/domain/identity_repository.dart';

enum IdentityLoadStatus { idle, loading, ready, failure }

final class IdentityController extends ChangeNotifier {
  IdentityController(this._repository);

  final IdentityRepository _repository;

  IdentityLoadStatus _status = IdentityLoadStatus.idle;
  IdentityOverview _overview = const IdentityOverview();
  bool _isSubmitting = false;
  final Set<IdentityDocumentType> _uploading = {};
  String? _errorMessage;
  String? _successMessage;

  IdentityLoadStatus get status => _status;
  IdentityOverview get overview => _overview;
  bool get isSubmitting => _isSubmitting;
  String? get errorMessage => _errorMessage;
  String? get successMessage => _successMessage;
  bool isUploading(IdentityDocumentType type) => _uploading.contains(type);

  Future<void> load() async {
    if (_status == IdentityLoadStatus.loading) {
      return;
    }
    _status = IdentityLoadStatus.loading;
    _errorMessage = null;
    notifyListeners();
    try {
      _overview = await _repository.loadOverview();
      _status = IdentityLoadStatus.ready;
    } catch (_) {
      _status = IdentityLoadStatus.failure;
      _errorMessage = '身份信息加载失败，请重试';
    }
    notifyListeners();
  }

  Future<PrivateIdentityFile?> upload(IdentityFileDraft draft) async {
    if (_uploading.contains(draft.purpose)) {
      return null;
    }
    _uploading.add(draft.purpose);
    _errorMessage = null;
    notifyListeners();
    try {
      return await _repository.uploadPrivateFile(draft);
    } catch (error) {
      _errorMessage = _message(error, '认证材料上传失败');
      return null;
    } finally {
      _uploading.remove(draft.purpose);
      notifyListeners();
    }
  }

  Future<bool> deleteDraft(String fileId) async {
    try {
      await _repository.deletePrivateDraft(fileId);
      return true;
    } catch (error) {
      _errorMessage = _message(error, '认证材料删除失败');
      notifyListeners();
      return false;
    }
  }

  Future<bool> submit(IdentityApplicationDraft application) async {
    if (_isSubmitting) {
      return false;
    }
    _isSubmitting = true;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();
    try {
      application.validate();
      await _repository.submitApplication(application);
      _overview = await _repository.loadOverview();
      _successMessage = '身份申请已提交，请等待审核';
      _status = IdentityLoadStatus.ready;
      return true;
    } catch (error) {
      _errorMessage = _message(error, '身份申请提交失败');
      return false;
    } finally {
      _isSubmitting = false;
      notifyListeners();
    }
  }

  String _message(Object error, String fallback) {
    if (error is ArgumentError && error.message != null) {
      return error.message.toString();
    }
    return fallback;
  }
}

enum ManagementLoadStatus { idle, loading, ready, denied, failure }

final class ManagementController extends ChangeNotifier {
  ManagementController(this._repository);

  final IdentityRepository _repository;

  ManagementLoadStatus _status = ManagementLoadStatus.idle;
  ManagementContext? _context;
  String? _errorMessage;

  ManagementLoadStatus get status => _status;
  ManagementContext? get context => _context;
  String? get errorMessage => _errorMessage;

  Future<void> enter() async {
    if (_status == ManagementLoadStatus.loading) {
      return;
    }
    _status = ManagementLoadStatus.loading;
    _context = null;
    _errorMessage = null;
    notifyListeners();
    try {
      final context = await _repository.loadManagementContext();
      _context = context;
      _status = context.hasAnyCapability
          ? ManagementLoadStatus.ready
          : ManagementLoadStatus.denied;
      if (!context.hasAnyCapability) {
        _errorMessage = '当前账号没有可用的专业管理能力';
      }
    } catch (_) {
      _status = ManagementLoadStatus.denied;
      _errorMessage = '专业身份无效、已撤销或尚未配置管理范围';
    }
    notifyListeners();
  }

  void clear() {
    _context = null;
    _status = ManagementLoadStatus.idle;
    _errorMessage = null;
  }
}
